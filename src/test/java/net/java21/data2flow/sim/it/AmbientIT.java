package net.java21.data2flow.sim.it;

import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.sim.heartbeat.HeartbeatCanary;
import net.java21.data2flow.sim.run.service.RunRetentionJob;
import net.java21.data2flow.sim.support.IntegrationTestSupport;
import net.java21.data2flow.sim.support.TestBeans;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 상시 보고(ALWAYS) 환경, 하트비트 카나리, 보관 기한 정리 */
class AmbientIT extends IntegrationTestSupport {

    @Autowired
    HeartbeatCanary heartbeat;
    @Autowired
    RunRetentionJob retention;
    @Autowired
    TestBeans.FakePurger purger;

    @Test
    @DisplayName("[SIM-02.01][AT-SIM-01.1][TC-SIM-015] 공간에 온습도 센서 2대 배치 → 60초(+지터) 안에 첫 원본, 측정 시각 = 실제 시각")
    void firstTelemetry() {
        space(1, "CLASSROOM");
        post("/internal/sim/devices", Map.of("deviceId", 11, "typeKey", "th-sensor", "spaceId", 1, "jitterPct", 10));
        post("/internal/sim/devices", Map.of("deviceId", 12, "typeKey", "th-sensor", "spaceId", 1, "jitterPct", 10));
        executor.round();
        rounds(7, Duration.ofSeconds(10));
        List<RawEnvelope> sent = raw.sent();
        assertThat(sent.stream().map(RawEnvelope::topic).distinct()).hasSize(2);
        assertThat(sent).allSatisfy(e -> {
            assertThat(e.simRunId()).isNull();
            assertThat(e.virtual()).isTrue();
            assertThat(e.receivedAt()).isBetween(clock.instant().minusSeconds(80), clock.instant());
        });
        JsonNode overview = get("/internal/sim/overview").response();
        assertThat(overview.get("spaces").get(0).get("current").get("temperature").asDouble()).isPositive();
        rounds(5, Duration.ofSeconds(10));   // 10틱마다 fCnt·배터리를 기기 설정에 남긴다
        assertThat(count("SELECT max(frame_counter) FROM data2flow_sim.devices")).isPositive();
    }

    @Test
    @DisplayName("[SIM-04.02][AT-SIM-08.2][TC-SIM-046] 시나리오가 쓰는 공간은 상시 보고를 멈춘다(BR-SIM-17)")
    void runTakesOver() {
        space(1, "CLASSROOM");
        post("/internal/sim/devices", Map.of("deviceId", 11, "typeKey", "th-sensor", "spaceId", 1, "reportIntervalSec", 10));
        executor.round();
        rounds(3, Duration.ofSeconds(10));
        long scenario = post("/internal/sim/scenarios", RunLifecycleIT.scenario("점유", List.of(1L), 3600, 1L, List.of()))
                .response().get("scenarioId").asLong();
        long run = post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 1)).response().get("runId").asLong();
        rounds(7, Duration.ofSeconds(10));
        raw.clear();
        rounds(6, Duration.ofSeconds(10));
        assertThat(raw.sent()).isNotEmpty().allSatisfy(e -> assertThat(e.simRunId()).isEqualTo(run));
    }

    @Test
    @DisplayName("[SIM-11.03][AT-SIM-13.4][TC-SIM-119] 종료 30일 1분 지난 실행 → 정리 요청·PURGED, 29일·연장 실행은 유지, 정리 요청 실패면 그대로")
    void retention() {
        space(1, "CLASSROOM");
        device(11, "th-sensor", 1, "온습도");
        long scenario = post("/internal/sim/scenarios", RunLifecycleIT.scenario("보관", List.of(1L), 3600, 1L, List.of()))
                .response().get("scenarioId").asLong();
        long[] runs = new long[3];
        for (int i = 0; i < 3; i++) {
            runs[i] = post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 60)).response().get("runId").asLong();
            for (int k = 0; k < 20 && !"COMPLETED".equals(get("/internal/sim/runs/" + runs[i]).response().get("status").asString()); k++) {
                clock.advance(Duration.ofSeconds(10));
                executor.round();
            }
        }
        Instant now = clock.instant();
        jdbc.sql("UPDATE data2flow_sim.runs SET retain_until = :t WHERE id = :id").param("t", java.sql.Timestamp.from(now.minusSeconds(60)))
                .param("id", runs[0]).update();
        jdbc.sql("UPDATE data2flow_sim.runs SET retain_until = :t WHERE id = :id").param("t", java.sql.Timestamp.from(now.plus(Duration.ofDays(1))))
                .param("id", runs[1]).update();
        assertThat(patch("/internal/sim/runs/" + runs[2] + "/report", Map.of("retainUntil", now.plus(Duration.ofDays(90)).toString()))
                .status()).isEqualTo(200);
        assertThat(patch("/internal/sim/runs/" + runs[2] + "/report", Map.of("retainUntil", now.plus(Duration.ofDays(500)).toString()))
                .status()).isEqualTo(400);
        purger.accept = false;
        assertThat(retention.purgeExpired()).isEmpty();
        purger.accept = true;
        assertThat(retention.purgeExpired()).containsExactly(runs[0]);
        assertThat(get("/internal/sim/runs/" + runs[0]).response().get("status").asString()).isEqualTo("PURGED");
        assertThat(get("/internal/sim/runs/" + runs[1]).response().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(get("/internal/sim/runs/" + runs[2]).response().get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("[ING-07.05][AT-ING-05.3][TC-ING-095] 하트비트 카나리: 시스템 가상 기기 __heartbeat__ 원본을 data2flow.raw에 넣는다(배포 조직, 실제 시각)")
    void heartbeat() {
        RawEnvelope e = heartbeat.beat().orElseThrow();
        assertThat(e.organizationId()).isEqualTo(1);
        assertThat(e.sourceId()).isEqualTo(3);
        assertThat(e.virtual()).isTrue();
        assertThat(e.receivedAt()).isEqualTo(clock.instant());
        JsonNode p = CODEC.mapper().readTree(e.payload());
        assertThat(p.get("deviceInfo").get("devEui").asString()).isEqualTo("__heartbeat__");
        assertThat(p.get("object").get("heartbeat").asDouble()).isEqualTo(1.0);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> heartbeat.lastSentAt() != null);
        assertThat(heartbeat.beat().orElseThrow().messageId()).isNotEqualTo(e.messageId());
    }
}
