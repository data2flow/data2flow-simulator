package net.java21.data2flow.sim.it;

import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 장애 주입(SIM-05.03·05.04, API-SIM-20·21) */
class FaultInjectionIT extends IntegrationTestSupport {

    long setupRun(List<Map<String, Object>> events) {
        space(1, "CLASSROOM");
        post("/internal/sim/devices", Map.of("deviceId", 11, "typeKey", "th-sensor", "spaceId", 1, "name", "온습도", "reportIntervalSec", 60,
                "overrides", Map.of("temperatureAccuracy", 0.0)));
        long scenario = post("/internal/sim/scenarios", RunLifecycleIT.scenario("장애 " + events.size(), List.of(1L), 3 * 3600, 5L, events))
                .response().get("scenarioId").asLong();
        return post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 60)).response().get("runId").asLong();
    }

    List<Double> stuckWindow(Instant from, Instant to) {
        return raw.sent().stream().filter(e -> e.topic().contains("5a1d00000000000b")).map(RawEnvelope::payload)
                .map(p -> CODEC.mapper().readTree(p)).filter(n -> {
                    Instant t = Instant.parse(n.get("time").asString());
                    return !t.isBefore(from) && t.isBefore(to);
                }).map(n -> n.get("object").get("temperature").asDouble()).toList();
    }

    @Test
    @DisplayName("[SIM-05.03][AT-SIM-11.1][TC-SIM-065] 시나리오 이벤트 STUCK과 실행 중 즉시 주입(API-SIM-20)이 같은 결과, 해제(API-SIM-21) 시 라벨 끝 고정")
    void eventAndImmediateAreSame() {
        Instant from = Instant.parse("2026-08-10T03:30:00Z");
        Instant to = from.plusSeconds(1800);
        long run = setupRun(List.of(Map.of("id", "stuck", "track", "FAULT", "at", from.toString(), "until", to.toString(),
                "target", Map.of("targetType", "DEVICE", "targetIds", List.of("11")), "params", Map.of("kind", "STUCK"))));
        rounds(200, Duration.ofSeconds(10));
        List<Double> viaEvent = stuckWindow(from, to);
        assertThat(viaEvent.stream().distinct()).hasSize(1);
        List<JsonNode> labels = events("sim.fault.started");
        assertThat(labels).hasSize(1);
        assertThat(labels.get(0).get("payload").get("kind").asString()).isEqualTo("STUCK");
        assertThat(labels.get(0).get("payload").get("simFrom").asString()).isEqualTo(from.toString());
        assertThat(labels.get(0).get("payload").get("runId").asLong()).isEqualTo(run);
        assertThat(events("sim.fault.ended")).hasSize(1);
        assertThat(get("/internal/sim/faults?runId=" + run).body().get("responses").get(0).get("status").asString()).isEqualTo("ENDED");

        // 즉시 주입: 실행 30분 시점에 STUCK 30분
        raw.clear();
        long run2 = post("/internal/sim/runs", Map.of("scenarioId", post("/internal/sim/scenarios",
                RunLifecycleIT.scenario("즉시 주입", List.of(1L), 3 * 3600, 5L, List.of())).response().get("scenarioId").asLong(),
                "acceleration", 60)).response().get("runId").asLong();
        clock.advance(Duration.ofSeconds(1));
        executor.round();
        rounds(29, Duration.ofSeconds(1));
        Result injected = post("/internal/sim/faults", Map.of("runId", run2, "targetType", "DEVICE", "targetIds", List.of("11"),
                "kind", "STUCK", "params", Map.of(), "startInSec", 0, "durationSec", 1800));
        assertThat(injected.status()).as(String.valueOf(injected.body())).isEqualTo(201);
        rounds(200, Duration.ofSeconds(10));
        List<Double> viaApi = stuckWindow(from.plusSeconds(60), to);
        assertThat(viaApi.stream().distinct()).hasSize(1);

        // 해제: 끝 시각을 해제 시각으로
        long run3 = post("/internal/sim/runs", Map.of("scenarioId", post("/internal/sim/scenarios",
                RunLifecycleIT.scenario("해제", List.of(1L), 3 * 3600, 5L, List.of())).response().get("scenarioId").asLong(),
                "acceleration", 60)).response().get("runId").asLong();
        clock.advance(Duration.ofSeconds(1));
        executor.round();
        String faultId = post("/internal/sim/faults", Map.of("runId", run3, "targetType", "DEVICE", "targetIds", List.of("11"),
                "kind", "DRIFT", "params", Map.of("perHour", 2), "durationSec", 3600)).response().get("faultIds").get(0).asString();
        rounds(10, Duration.ofSeconds(1));
        JsonNode cancelled = post("/internal/sim/faults/" + faultId + "/cancel", Map.of()).response();
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(Instant.parse(cancelled.get("simTo").asString())).isBefore(Instant.parse(cancelled.get("simFrom").asString()).plusSeconds(3600));
        assertThat(post("/internal/sim/faults/" + faultId + "/cancel", Map.of()).code()).isEqualTo("SIM_RUN_STATE_CONFLICT");
    }

    @Test
    @DisplayName("[SIM-05.01][AT-SIM-11.1][TC-SIM-060] 실제 기기(시뮬레이터에 없는 기기)·모르는 게이트웨이 → SIM_TARGET_NOT_VIRTUAL, 강도 범위 밖 → SIM_PROPERTY_OUT_OF_RANGE")
    void guards() {
        space(1, "CLASSROOM");
        device(11, "th-sensor", 1, "온습도");
        assertThat(post("/internal/sim/faults", Map.of("targetType", "DEVICE", "targetIds", List.of("999"), "kind", "STUCK",
                "durationSec", 600)).code()).isEqualTo("SIM_TARGET_NOT_VIRTUAL");
        assertThat(post("/internal/sim/faults", Map.of("targetType", "GATEWAY", "targetIds", List.of("aa55"), "kind", "GATEWAY_DOWN",
                "durationSec", 600)).code()).isEqualTo("SIM_TARGET_NOT_VIRTUAL");
        assertThat(post("/internal/sim/faults", Map.of("targetType", "DEVICE", "targetIds", List.of("11"), "kind", "DUPLICATE",
                "params", Map.of("factor", 9), "durationSec", 600)).code()).isEqualTo("SIM_PROPERTY_OUT_OF_RANGE");
        assertThat(post("/internal/sim/faults", Map.of("targetType", "DEVICE", "targetIds", List.of("11"), "kind", "NOPE",
                "durationSec", 600)).status()).isEqualTo(400);
        // 상시 모드(실행 없음) 주입: 게이트웨이 장애
        Result ok = post("/internal/sim/faults", Map.of("targetType", "GATEWAY", "targetIds", List.of("5a1d00ff00000001"),
                "kind", "GATEWAY_DOWN", "durationSec", 600));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(get("/internal/sim/faults?status=SCHEDULED").body().get("totalCount").asLong()).isEqualTo(1);
    }
}
