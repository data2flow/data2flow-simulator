package net.java21.data2flow.sim.it;

import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 결정적 재현(SIM-08.03): 같은 시드 → 같은 SHA-256, 가속·인스턴스 교체와 무관 */
class DeterminismIT extends IntegrationTestSupport {

    long scenario(String name, long seed) {
        Result r = post("/internal/sim/scenarios", RunLifecycleIT.scenario(name, List.of(1L), 3 * 3600, seed, List.of(
                Map.of("id", "in", "track", "OCCUPANCY", "at", "2026-08-10T03:30:00Z", "until", "2026-08-10T05:00:00Z",
                        "target", Map.of("spaceId", 1), "params", Map.of("count", 25)),
                Map.of("id", "ac", "track", "ACTUATOR", "at", "2026-08-10T04:00:00Z", "target", Map.of("deviceId", 20),
                        "params", Map.of("capability", "Thermostat", "command", "set", "args", Map.of("mode", "cool", "targetTemperature", 24))),
                Map.of("id", "f1", "track", "FAULT", "at", "2026-08-10T04:10:00Z", "until", "2026-08-10T04:40:00Z",
                        "target", Map.of("targetType", "DEVICE", "targetIds", List.of("11")), "params", Map.of("kind", "STUCK")),
                Map.of("id", "f2", "track", "FAULT", "at", "2026-08-10T03:20:00Z", "until", "2026-08-10T05:20:00Z",
                        "target", Map.of("targetType", "DEVICE", "targetIds", List.of("12")),
                        "params", Map.of("kind", "DROPOUT", "params", Map.of("ratio", 0.3))),
                Map.of("id", "f3", "track", "FAULT", "at", "2026-08-10T03:05:00Z", "until", "2026-08-10T05:00:00Z",
                        "target", Map.of("targetType", "DEVICE", "targetIds", List.of("13")),
                        "params", Map.of("kind", "SPIKE", "params", Map.of("magnitude", 300, "count", 2))))));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.response().get("scenarioId").asLong();
    }

    void devices() {
        space(1, "CLASSROOM");
        for (int i = 1; i <= 19; i++) {
            String type = switch (i % 4) {
                case 0 -> "pir-sensor";
                case 1 -> "th-sensor";
                case 2 -> "co2-sensor";
                default -> "am107";
            };
            post("/internal/sim/devices", Map.of("deviceId", 10 + i, "typeKey", type, "spaceId", 1, "name", "기기-" + i,
                    "reportIntervalSec", 60, "jitterPct", 10));
        }
        post("/internal/sim/devices", Map.of("deviceId", 20, "typeKey", "aircon", "spaceId", 1, "name", "에어컨",
                "response", Map.of("ackDelayMs", 300, "failurePct", 30)));
    }

    String runToEnd(long scenarioId, long seed, int acceleration, Duration step) {
        long run = post("/internal/sim/runs", Map.of("scenarioId", scenarioId, "acceleration", acceleration, "seed", seed))
                .response().get("runId").asLong();
        for (int i = 0; i < 5000; i++) {
            clock.advance(step);
            executor.round();
            if ("COMPLETED".equals(get("/internal/sim/runs/" + run).response().get("status").asString())) {
                break;
            }
        }
        return get("/internal/sim/runs/" + run + "/report").response().get("dataSha256").asString();
    }

    @Test
    @DisplayName("[SIM-08.03][AT-SIM-08.3][TC-SIM-089] 같은 시드로 2회(x60, 기기 20대, 장애 3개) → 해시 동일, x10 vs x60도 동일, 다른 시드는 다름")
    void sameSeedSameHash() {
        devices();
        long scenario = scenario("결정성", 42);
        String a = runToEnd(scenario, 42, 60, Duration.ofSeconds(10));
        String b = runToEnd(scenario, 42, 60, Duration.ofSeconds(7));
        String c = runToEnd(scenario, 42, 10, Duration.ofSeconds(60));
        String d = runToEnd(scenario, 43, 60, Duration.ofSeconds(10));
        assertThat(a).hasSize(64).isEqualTo(b).isEqualTo(c);
        assertThat(d).isNotEqualTo(a);
    }

    @Test
    @DisplayName("[SIM-04.02][AT-SIM-08.2][TC-SIM-045] 실행 중 인스턴스가 죽어도(kill -9) 다른 인스턴스가 마지막 체크포인트에서 이어 실행, 해시 동일, 공백 ≤ 10틱")
    void resumeAfterCrash() {
        devices();
        long scenario = scenario("이어 실행", 7);
        String straight = runToEnd(scenario, 7, 60, Duration.ofSeconds(10));
        long run = post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 60, "seed", 7)).response().get("runId").asLong();
        rounds(30, Duration.ofSeconds(1));
        long checkpointTick = jdbc.sql("SELECT (checkpoint->'world'->>'tick')::bigint FROM data2flow_sim.runs WHERE id = :id")
                .param("id", run).query(Long.class).single();
        assertThat(checkpointTick).isPositive();
        // kill -9: 메모리의 실행을 잃고, 소유(lease) 기한이 지난 뒤 다른 인스턴스가 집는다
        executor.reset();
        jdbc.sql("UPDATE data2flow_sim.leases SET owner = 'simulator-test-dead' WHERE name = :n").param("n", "run:" + run).update();
        clock.advance(Duration.ofSeconds(31));
        executor.round();
        String status = get("/internal/sim/runs/" + run).response().get("simClock").asString();
        assertThat(java.time.Instant.parse(status)).isAfterOrEqualTo(java.time.Instant.parse("2026-08-10T03:00:00Z").plusSeconds(checkpointTick * 10));
        for (int i = 0; i < 5000; i++) {
            clock.advance(Duration.ofSeconds(10));
            executor.round();
            if ("COMPLETED".equals(get("/internal/sim/runs/" + run).response().get("status").asString())) {
                break;
            }
        }
        assertThat(get("/internal/sim/runs/" + run + "/report").response().get("dataSha256").asString()).isEqualTo(straight);
    }

    @Test
    @DisplayName("[SIM-08.03][AT-SIM-08.3][TC-SIM-045] data2flow.raw confirm이 실패하면 마지막 정상 상태에서 같은 메시지를 다시 만든다(해시 동일)")
    void retryAfterPublishFailure() {
        devices();
        long scenario = scenario("발행 실패", 9);
        String straight = runToEnd(scenario, 9, 60, Duration.ofSeconds(10));
        long run = post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 60, "seed", 9)).response().get("runId").asLong();
        rounds(5, Duration.ofSeconds(10));
        raw.failing(true);
        rounds(3, Duration.ofSeconds(10));
        raw.failing(false);
        for (int i = 0; i < 5000; i++) {
            clock.advance(Duration.ofSeconds(10));
            executor.round();
            if ("COMPLETED".equals(get("/internal/sim/runs/" + run).response().get("status").asString())) {
                break;
            }
        }
        assertThat(get("/internal/sim/runs/" + run + "/report").response().get("dataSha256").asString()).isEqualTo(straight);
    }
}
