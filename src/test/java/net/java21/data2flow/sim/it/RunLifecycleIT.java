package net.java21.data2flow.sim.it;

import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RunLifecycleIT extends IntegrationTestSupport {

    static final String START = "2026-08-10T03:00:00Z";

    static Map<String, Object> scenario(String name, List<Long> spaces, int durationSec, Long seed, List<Map<String, Object>> events) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("spaceIds", spaces);
        s.put("simStartAt", START);
        s.put("durationSec", durationSec);
        s.put("seed", seed);
        s.put("outdoor", Map.of("mode", "DIURNAL", "diurnal", Map.of("max", 35, "min", 27, "peakHour", 15, "humidity", 55)));
        s.put("events", events);
        return s;
    }

    long prepare(long spaceId, String name) {
        space(spaceId, "CLASSROOM");
        device(spaceId * 10 + 1, "th-sensor", spaceId, name + " 온습도");
        device(spaceId * 10 + 2, "aircon", spaceId, name + " 에어컨");
        Result r = post("/internal/sim/scenarios", scenario(name, List.of(spaceId), 3600, 42L, List.of(Map.of("id", "in",
                "track", "OCCUPANCY", "at", "2026-08-10T03:10:00Z", "target", Map.of("spaceId", spaceId), "params", Map.of("count", 20)))));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return Long.parseLong(r.response().get("scenarioId").asString());
    }

    long start(long scenarioId, int acceleration) {
        Result r = post("/internal/sim/runs", Map.of("scenarioId", scenarioId, "acceleration", acceleration));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        assertThat(r.response().get("status").asString()).isEqualTo("RUNNING");
        return Long.parseLong(r.response().get("runId").asString());
    }

    @Test
    @DisplayName("[SIM-02.07][AT-SIM-05.6][TC-SIM-028] 내부 직접 주입: data2flow.raw에 SIM 소스(SIMULATION·virtual·simRunId)로 기록, ingress 우회")
    void internalInject() {
        long run = start(prepare(100, "주입"), 60);
        rounds(3, Duration.ofSeconds(1));
        List<RawEnvelope> sent = raw.sent();
        assertThat(sent).isNotEmpty().allSatisfy(e -> {
            assertThat(e.sourceType()).isEqualTo("SIMULATION");
            assertThat(e.virtual()).isTrue();
            assertThat(e.simRunId()).isEqualTo(run);
            assertThat(e.sourceId()).isEqualTo(3);
            assertThat(e.organizationId()).isEqualTo(ORG);
            assertThat(e.ingressInstance()).isEqualTo("simulator-test-0");
            assertThat(e.dedupKey()).startsWith("chirpstack:");
            assertThat(e.receivedAt()).isAfterOrEqualTo(Instant.parse(START));
        });
        assertThat(events("sim.run.started")).hasSize(1);
    }

    @Test
    @DisplayName("[SIM-04.03][AT-SIM-08.1][TC-SIM-049] x60이면 실제 1초에 시뮬레이션 60초, 1시간 시나리오는 실제 60초에 COMPLETED, 측정 시각은 시뮬레이션 날짜")
    void accelerationAndCompletion() {
        long run = start(prepare(100, "가속"), 60);
        rounds(10, Duration.ofSeconds(1));
        JsonNode status = get("/internal/sim/runs/" + run).response();
        assertThat(Instant.parse(status.get("simClock").asString())).isEqualTo(Instant.parse(START).plusSeconds(600));
        rounds(50, Duration.ofSeconds(1));
        JsonNode done = get("/internal/sim/runs/" + run).response();
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("progressPct").asDouble()).isEqualTo(100.0);
        assertThat(raw.sent()).allSatisfy(e -> assertThat(e.receivedAt()).isBetween(Instant.parse(START), Instant.parse(START).plusSeconds(3600)));
        assertThat(events("sim.run.completed")).hasSize(1);
        JsonNode report = get("/internal/sim/runs/" + run + "/report").response();
        assertThat(report.get("dataSha256").asString()).hasSize(64);
        assertThat(report.get("metrics").get("energyKwh").asDouble()).isPositive();
        assertThat(report.get("partial").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("[SIM-04.02][AT-SIM-08.2][TC-SIM-044] 일시정지 3분(실제) 동안 시뮬레이션 시각 정지, 재개 후 이어짐(공백 없음), 상태 이벤트 순서")
    void pauseResume() {
        long run = start(prepare(100, "일시정지"), 60);
        rounds(5, Duration.ofSeconds(1));
        assertThat(post("/internal/sim/runs/" + run + "/pause", Map.of()).response().get("status").asString()).isEqualTo("PAUSED");
        executor.round();
        Instant paused = Instant.parse(get("/internal/sim/runs/" + run).response().get("simClock").asString());
        rounds(18, Duration.ofSeconds(10));
        assertThat(Instant.parse(get("/internal/sim/runs/" + run).response().get("simClock").asString())).isEqualTo(paused);
        assertThat(post("/internal/sim/runs/" + run + "/pause", Map.of()).code()).isEqualTo("SIM_RUN_STATE_CONFLICT");
        assertThat(post("/internal/sim/runs/" + run + "/resume", Map.of()).response().get("status").asString()).isEqualTo("RUNNING");
        executor.round();
        rounds(2, Duration.ofSeconds(1));
        assertThat(Instant.parse(get("/internal/sim/runs/" + run).response().get("simClock").asString())).isEqualTo(paused.plusSeconds(120));
        List<String> types = events(null).stream().map(e -> e.get("type").asString()).filter(t -> t.startsWith("sim.run.")).toList();
        assertThat(types).containsSubsequence("sim.run.started", "sim.run.paused", "sim.run.resumed");
        // 시뮬레이션 시각 기준 데이터 공백 없음: 온습도 보고 간격이 모두 70초 이하
        List<Instant> times = raw.sent().stream().filter(e -> e.topic().contains("5a1d0000000003e9")).map(RawEnvelope::receivedAt).sorted().toList();
        for (int i = 1; i < times.size(); i++) {
            assertThat(Duration.between(times.get(i - 1), times.get(i)).toSeconds()).isLessThanOrEqualTo(70);
        }
    }

    @Test
    @DisplayName("[SIM-04.02][AT-SIM-08.2][TC-SIM-046] 정지 → 끝난 구간까지 판정(partial), 초기화 → 처음 값, 같은 공간 실행 중이면 SIM_SPACE_BUSY")
    void stopResetBusy() {
        long scenario = prepare(100, "정지");
        long run = start(scenario, 60);
        rounds(5, Duration.ofSeconds(1));
        assertThat(post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 10)).code()).isEqualTo("SIM_SPACE_BUSY");
        assertThat(post("/internal/sim/runs/" + run + "/stop", Map.of()).response().get("status").asString()).isEqualTo("STOPPED");
        executor.round();
        JsonNode report = get("/internal/sim/runs/" + run + "/report").response();
        assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(report.get("partial").asBoolean()).isTrue();
        assertThat(post("/internal/sim/runs/" + run + "/reset", Map.of()).response().get("status").asString()).isEqualTo("CREATED");
        JsonNode reset = get("/internal/sim/runs/" + run).response();
        assertThat(Instant.parse(reset.get("simClock").asString())).isEqualTo(Instant.parse(START));
        assertThat(reset.get("progressPct").asDouble()).isZero();
        assertThat(post("/internal/sim/runs/" + run + "/start", Map.of()).response().get("status").asString()).isEqualTo("RUNNING");
        rounds(2, Duration.ofSeconds(1));
        assertThat(Instant.parse(get("/internal/sim/runs/" + run).response().get("simClock").asString()))
                .isAfter(Instant.parse(START));
        assertThat(get("/internal/sim/runs/999999").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[SIM-11.01][AT-SIM-08.4][TC-SIM-115] 동시에 시작 10건 → 정확히 5건 RUNNING, 나머지 409 SIM_CONCURRENT_RUN_LIMIT")
    void concurrentLimit() throws Exception {
        List<Long> scenarios = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            scenarios.add(prepare(200 + i, "동시-" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Result>> futures = new ArrayList<>();
        for (long id : scenarios) {
            Callable<Result> call = () -> post("/internal/sim/runs", Map.of("scenarioId", id, "acceleration", 1));
            futures.add(pool.submit(call));
        }
        int created = 0;
        int limited = 0;
        for (Future<Result> f : futures) {
            Result r = f.get();
            if (r.status() == 201) {
                created++;
            } else if ("SIM_CONCURRENT_RUN_LIMIT".equals(r.code())) {
                limited++;
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(5);
        assertThat(limited).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM data2flow_sim.runs WHERE status = 'RUNNING'")).isEqualTo(5);
    }

    @Test
    @DisplayName("[SIM-11.01][AT-SIM-08.4][TC-SIM-114] 가속 범위 밖(x0·x61) 400, 실행 중 가속 변경, 외기 WEATHER는 SIM_WEATHER_DATA_MISSING")
    void accelerationValidation() {
        long scenario = prepare(100, "가속 검사");
        assertThat(post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 0)).status()).isEqualTo(400);
        assertThat(post("/internal/sim/runs", Map.of("scenarioId", scenario, "acceleration", 61)).status()).isEqualTo(400);
        long run = start(scenario, 10);
        rounds(2, Duration.ofSeconds(1));
        JsonNode changed = patch("/internal/sim/runs/" + run, Map.of("acceleration", 60)).response();
        assertThat(changed.get("accelerationEffective").asInt()).isEqualTo(60);
        Instant before = Instant.parse(get("/internal/sim/runs/" + run).response().get("simClock").asString());
        executor.round();
        rounds(1, Duration.ofSeconds(1));
        Instant after = Instant.parse(get("/internal/sim/runs/" + run).response().get("simClock").asString());
        assertThat(Duration.between(before, after).toSeconds()).isEqualTo(60);

        space(300, "OFFICE");
        Map<String, Object> weather = scenario("날씨", List.of(300L), 3600, 1L, List.of());
        weather.put("outdoor", Map.of("mode", "WEATHER", "weather", Map.of("from", "2025-08-11", "to", "2025-08-17")));
        long id = Long.parseLong(post("/internal/sim/scenarios", weather).response().get("scenarioId").asString());
        assertThat(post("/internal/sim/runs", Map.of("scenarioId", id)).code()).isEqualTo("SIM_WEATHER_DATA_MISSING");
    }
}
