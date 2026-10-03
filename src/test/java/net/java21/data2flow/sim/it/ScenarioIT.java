package net.java21.data2flow.sim.it;

import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 시나리오 저장·복제·내보내기·가져오기(SIM-04.01, SIM-08.02) */
class ScenarioIT extends IntegrationTestSupport {

    List<Map<String, Object>> events() {
        return List.of(
                Map.of("id", "in", "track", "OCCUPANCY", "at", "2026-08-10T03:10:00Z", "until", "2026-08-10T04:00:00Z",
                        "target", Map.of("spaceId", 1), "params", Map.of("count", 30)),
                Map.of("id", "win", "track", "OPENING", "at", "2026-08-10T03:20:00Z", "until", "2026-08-10T03:30:00Z",
                        "target", Map.of("spaceId", 1), "params", Map.of("opening", "WINDOW", "open", true)),
                Map.of("id", "ac", "track", "ACTUATOR", "at", "2026-08-10T03:40:00Z", "target", Map.of("deviceId", 15),
                        "params", Map.of("capability", "Switch", "command", "on")),
                Map.of("id", "f", "track", "FAULT", "at", "2026-08-10T03:15:00Z", "until", "2026-08-10T03:45:00Z",
                        "target", Map.of("targetType", "DEVICE", "targetIds", List.of("11")), "params", Map.of("kind", "STUCK")));
    }

    void setup(long org) {
        put(org, "/internal/sim/spaces/1", Map.of("preset", "CLASSROOM"));
        for (Object[] d : new Object[][]{{11, "th-sensor", "온습도"}, {15, "aircon", "에어컨"}}) {
            assertThat(exchange(api(org).post().uri("/internal/sim/devices").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("deviceId", d[0], "typeKey", d[1], "spaceId", 1, "name", d[2], "reportIntervalSec", 60)))
                    .status()).isEqualTo(201);
        }
    }

    Result put(long org, String path, Object body) {
        return exchange(api(org).put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @Test
    @DisplayName("[SIM-04.01][AT-SIM-07.1][TC-SIM-041] 재실·문/창문·수동 조작·장애 트랙 저장 → 다시 조회 시 이벤트 동일, 복제 시 새 ID·이름 \"(복사본)\", 낙관적 잠금")
    void crud() {
        setup(ORG);
        Map<String, Object> body = RunLifecycleIT.scenario("강의실 하루", List.of(1L), 3600, 42L, events());
        Result created = post("/internal/sim/scenarios", body);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        long id = created.response().get("scenarioId").asLong();
        JsonNode got = get("/internal/sim/scenarios/" + id).response();
        assertThat(got.get("events")).hasSize(4);
        assertThat(got.get("events").get(1).get("params").get("opening").asString()).isEqualTo("WINDOW");
        assertThat(got.get("events").toString()).isEqualTo(created.response().get("events").toString());
        JsonNode clone = post("/internal/sim/scenarios/" + id + "/clone", Map.of()).response();
        assertThat(get("/internal/sim/scenarios/" + clone.get("id").asString()).response().get("name").asString())
                .isEqualTo("강의실 하루 (복사본)");
        assertThat(post("/internal/sim/scenarios", body).code()).isEqualTo("VERSION_CONFLICT");
        Map<String, Object> changed = new java.util.LinkedHashMap<>(body);
        changed.put("durationSec", 7200);
        changed.put("baseVersion", 0);
        assertThat(put("/internal/sim/scenarios/" + id, changed).response().get("version").asInt()).isEqualTo(1);
        assertThat(put("/internal/sim/scenarios/" + id, changed).code()).isEqualTo("VERSION_CONFLICT");
        Map<String, Object> bad = RunLifecycleIT.scenario("잘못", List.of(1L), 3600, 1L, List.of(Map.of("id", "x", "track",
                "OCCUPANCY", "at", "2027-01-01T00:00:00Z", "target", Map.of("spaceId", 1), "params", Map.of("count", 3))));
        Result invalid = post("/internal/sim/scenarios", bad);
        assertThat(invalid.code()).isEqualTo("SIM_SCENARIO_INVALID");
        assertThat(invalid.body().get("errors").get(0).get("field").asString()).isEqualTo("events[0].at");
        assertThat(get("/internal/sim/scenarios?keyword=강의실").body().get("totalCount").asLong()).isEqualTo(2);
        assertThat(delete("/internal/sim/scenarios/" + clone.get("id").asString()).status()).isEqualTo(204);
        assertThat(get("/internal/sim/presets").response()).hasSize(5);
    }

    @Test
    @DisplayName("[SIM-08.02][AT-SIM-15.1][TC-SIM-086] JSON·YAML 내보내기 → 스키마 버전·공간 물리·기기 설정·시나리오·시드, 스키마 버전 불일치 → SIM_IMPORT_INVALID")
    void export() {
        setup(ORG);
        long id = post("/internal/sim/scenarios", RunLifecycleIT.scenario("내보내기", List.of(1L), 3600, 42L, events()))
                .response().get("scenarioId").asLong();
        byte[] json = api().get().uri("/internal/sim/scenarios/" + id + "/export").retrieve().body(byte[].class);
        JsonNode file = CODEC.mapper().readTree(json);
        assertThat(file.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(file.get("spaces").get(0).get("physics").get("areaM2").asDouble()).isEqualTo(66.0);
        assertThat(file.get("devices")).hasSize(2);
        assertThat(file.get("seed").asLong()).isEqualTo(42);
        byte[] yaml = api().get().uri("/internal/sim/scenarios/" + id + "/export?format=yaml").retrieve().body(byte[].class);
        assertThat(new String(yaml, StandardCharsets.UTF_8)).contains("schemaVersion: 1");
        Result dry = upload(ORG, yaml, true, null);
        assertThat(dry.response().get("willCreate").get("devices").asInt()).isEqualTo(2);
        assertThat(dry.response().get("conflicts").get(0).get("resolution").asString()).isEqualTo("RENAME");
        Result wrongVersion = upload(ORG, "{\"schemaVersion\": 9, \"scenario\": {}}".getBytes(StandardCharsets.UTF_8), true, null);
        assertThat(wrongVersion.code()).isEqualTo("SIM_IMPORT_INVALID");
        assertThat(wrongVersion.body().get("errors").get(0).get("field").asString()).isEqualTo("schemaVersion");
        assertThat(upload(ORG, "not: [json".getBytes(StandardCharsets.UTF_8), true, null).code()).isEqualTo("SIM_IMPORT_INVALID");
    }

    @Test
    @DisplayName("[SIM-08.02][AT-SIM-15.1][TC-SIM-087] 조직 A에서 시드 고정 시나리오 내보내기 → 조직 B 가져와 실행 → 생성 값 해시 동일(기기 ID만 다름)")
    void crossOrganization() {
        setup(1);
        long id = post("/internal/sim/scenarios", RunLifecycleIT.scenario("이관", List.of(1L), 3600, 42L, events()))
                .response().get("scenarioId").asLong();
        byte[] json = api().get().uri("/internal/sim/scenarios/" + id + "/export").retrieve().body(byte[].class);
        String idMap = "{\"spaces\":{\"1\":501},\"devices\":{\"11\":5011,\"15\":5015}}";
        Result imported = upload(2, json, false, idMap);
        assertThat(imported.status()).as(String.valueOf(imported.body())).isEqualTo(201);
        long importedScenario = imported.response().get("scenarioId").asLong();
        assertThat(imported.response().get("deviceIds")).hasSize(2);

        String hashA = runToEnd(1, id);
        String hashB = runToEnd(2, importedScenario);
        assertThat(hashB).hasSize(64).isEqualTo(hashA);
    }

    String runToEnd(long org, long scenarioId) {
        long run = exchange(api(org).post().uri("/internal/sim/runs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("scenarioId", scenarioId, "acceleration", 60))).response().get("runId").asLong();
        for (int i = 0; i < 100; i++) {
            clock.advance(Duration.ofSeconds(10));
            executor.round();
            if ("COMPLETED".equals(exchange(api(org).get().uri("/internal/sim/runs/" + run)).response().get("status").asString())) {
                break;
            }
        }
        return exchange(api(org).get().uri("/internal/sim/runs/" + run + "/report")).response().get("dataSha256").asString();
    }

    Result upload(long org, byte[] content, boolean dryRun, String idMap) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "scenario.json";
            }
        });
        if (idMap != null) {
            parts.add("idMap", idMap);
        }
        return exchange(api(org).post().uri("/internal/sim/imports?dryRun=" + dryRun).contentType(MediaType.MULTIPART_FORM_DATA).body(parts));
    }
}
