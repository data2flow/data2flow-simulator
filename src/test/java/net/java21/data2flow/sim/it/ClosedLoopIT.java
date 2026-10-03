package net.java21.data2flow.sim.it;

import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 시연 "폭염 오후" x60 폐루프를 simulator의 정식 경계로 확인한다(SIM-03.03, SIM-04.05, TC-SIM-034의 simulator 몫):
 * 프리셋 준비(API-SIM-18) → 실행(API-SIM-34) → data2flow.raw에 나간 가상 센서 원본을 읽어 "27℃ 이상 5분" 판단(플로우 자리) →
 * virtual 드라이버처럼 API-SIM-30으로 명령 → device.command.ack·device.state.reported(EVT-SIM-03) → 가상 온도 하강·안정.
 * flow-engine·action·pipeline까지 붙인 compose E2E는 manifests/tests 몫이다.
 */
class ClosedLoopIT extends IntegrationTestSupport {

    @Test
    @DisplayName("[SIM-03.03][AT-SIM-06.1][TC-SIM-034] 폭염 오후 x60: 27℃ 5분 → 가상 에어컨 냉방 24℃ → 온도 하강 → 24±1℃, 사람 개입 없음")
    void heatwave() {
        space(1, "CLASSROOM");
        List<Map<String, Object>> devices = List.of(
                Map.of("deviceId", 11, "typeKey", "th-sensor"), Map.of("deviceId", 12, "typeKey", "th-sensor"),
                Map.of("deviceId", 13, "typeKey", "co2-sensor"), Map.of("deviceId", 14, "typeKey", "pir-sensor"),
                Map.of("deviceId", 15, "typeKey", "aircon"));
        Result prepared = post("/internal/sim/presets/heatwave-afternoon/prepare", Map.of("spaceId", 1, "devices", devices));
        assertThat(prepared.status()).as(String.valueOf(prepared.body())).isEqualTo(200);
        assertThat(prepared.response().get("reused").asBoolean()).isFalse();
        JsonNode flow = prepared.response().get("flowTemplates").get(0);
        assertThat(flow.get("templateKey").asString()).isEqualTo("hot-then-cool");
        assertThat(flow.get("bindings").get("airconId").asLong()).isEqualTo(15);
        assertThat(post("/internal/sim/presets/heatwave-afternoon/prepare", Map.of("spaceId", 1, "devices", devices))
                .response().get("reused").asBoolean()).isTrue();
        long scenarioId = prepared.response().get("scenarioId").asLong();
        Result started = post("/internal/sim/runs", Map.of("scenarioId", scenarioId, "acceleration", 60));
        long runId = started.response().get("runId").asLong();

        String[] sensors = {"5a1d00000000000b", "5a1d00000000000c"};
        TreeMap<Instant, Double> temps = new TreeMap<>();
        Instant hotSince = null;
        Instant commandedAt = null;
        int seen = 0;
        for (int round = 0; round < 200; round++) {
            clock.advance(Duration.ofSeconds(10));
            executor.round();
            List<RawEnvelope> sent = raw.sent();
            for (RawEnvelope e : sent.subList(seen, sent.size())) {
                JsonNode p = CODEC.mapper().readTree(e.payload());
                String dev = p.path("deviceInfo").path("devEui").asString();
                if ((dev.equals(sensors[0]) || dev.equals(sensors[1])) && p.path("object").has("temperature")) {
                    temps.put(Instant.parse(p.get("time").asString()), p.get("object").get("temperature").asDouble());
                }
            }
            seen = sent.size();
            if (commandedAt == null && !temps.isEmpty()) {
                Map.Entry<Instant, Double> last = temps.lastEntry();
                if (last.getValue() >= 27) {
                    hotSince = hotSince == null ? last.getKey() : hotSince;
                    if (Duration.between(hotSince, last.getKey()).toMinutes() >= 5) {
                        Result cmd = post(driver(), "/internal/sim/devices/15/commands", Map.of("commandId",
                                "8f1c2d3e-0000-4000-8000-000000000001", "capability", "Thermostat", "command", "set",
                                "args", Map.of("mode", "cool", "targetTemperature", 24), "desiredVersion", 1));
                        assertThat(cmd.status()).isEqualTo(202);
                        assertThat(cmd.response().get("accepted").asBoolean()).isTrue();
                        commandedAt = last.getKey();
                    }
                } else {
                    hotSince = null;
                }
            }
            if ("COMPLETED".equals(get("/internal/sim/runs/" + runId).response().get("status").asString())) {
                break;
            }
        }
        assertThat(commandedAt).as("27℃ 이상 5분").isNotNull();
        List<JsonNode> acks = events("device.command.ack");
        assertThat(acks).hasSize(1);
        assertThat(acks.get(0).get("payload").get("result").asString()).isEqualTo("ACKED");
        assertThat(acks.get(0).get("payload").get("commandId").asString()).isEqualTo("8f1c2d3e-0000-4000-8000-000000000001");
        assertThat(acks.get(0).get("organizationId").asLong()).isEqualTo(ORG);
        List<JsonNode> reported = events("device.state.reported");
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).get("payload").get("capabilities").get("Switch").get("on").asBoolean()).isTrue();
        assertThat(reported.get(0).get("payload").get("version").asLong()).isEqualTo(1);
        assertThat(reported.get(0).get("payload").get("virtual").asBoolean()).isTrue();

        Instant cmdAt = commandedAt;
        double peak = temps.tailMap(cmdAt).values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        List<Double> tail = new ArrayList<>(temps.tailMap(temps.lastKey().minus(Duration.ofMinutes(30))).values());
        double avg = tail.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertThat(avg).isLessThan(peak).isBetween(23.0, 25.0);

        JsonNode report = get("/internal/sim/runs/" + runId + "/report").response();
        assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(report.get("metrics").get("controlCount").asLong()).isEqualTo(1);
        JsonNode acOn = report.get("expectations").get(0);
        assertThat(acOn.get("id").asString()).isEqualTo("ac-on");
        assertThat(acOn.get("passed").asBoolean()).isTrue();
        assertThat(report.get("passed").asBoolean()).isTrue();
        JsonNode state = driver().get().uri("/internal/sim/devices/15/state").exchange((q, s) -> CODEC.mapper().readTree(s.getBody()))
                .get("response");
        assertThat(state.get("capabilities").get("Thermostat").get("mode").asString()).isEqualTo("cool");
    }
}
