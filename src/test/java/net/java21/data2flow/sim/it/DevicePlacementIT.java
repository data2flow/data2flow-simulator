package net.java21.data2flow.sim.it;

import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevicePlacementIT extends IntegrationTestSupport {

    @Test
    @DisplayName("[SIM-09.07][AT-SIM-02.1][TC-SIM-104] 표준 강의실 키트 → 7대(온습도 2·CO2 1·재실 1·에어컨 1·공기청정기 1·환기 1), 측정·제어 관계, 제안 플로우")
    void kit() {
        space(100, "CLASSROOM");
        List<Map<String, Object>> devices = new ArrayList<>();
        long id = 1000;
        for (String t : List.of("th-sensor", "th-sensor", "co2-sensor", "pir-sensor", "aircon", "air-purifier", "ventilator")) {
            devices.add(Map.of("deviceId", id++, "typeKey", t));
        }
        Result r = post("/internal/sim/kits/classroom-standard/place", Map.of("spaceId", 100, "devices", devices));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        JsonNode body = r.response();
        assertThat(body.get("devices")).hasSize(7);
        assertThat(body.get("devices").get(4).get("deviceId").isString()).isTrue();   // api-rules: ID는 JSON 문자열
        assertThat(body.get("devices").get(4).get("deviceId").asString()).isEqualTo("1004");
        assertThat(body.get("devices").get(0).get("relation").asString()).isEqualTo("MEASURES");
        assertThat(body.get("devices").get(4).get("relation").asString()).isEqualTo("CONTROLS");
        JsonNode flow = body.get("suggestedFlows").get(0);
        assertThat(flow.get("templateKey").asString()).isEqualTo("hot-then-cool");
        assertThat(flow.get("bindings").get("airconId").asString()).isEqualTo("1004");
        assertThat(count("SELECT count(*) FROM data2flow_sim.devices WHERE space_id = 100")).isEqualTo(7);

        // 키트 구성과 다르면 한 대도 만들지 않는다
        Result wrong = post("/internal/sim/kits/classroom-standard/place", Map.of("spaceId", 100,
                "devices", List.of(Map.of("deviceId", 2000, "typeKey", "aircon"))));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(post("/internal/sim/kits/nope/place", Map.of("spaceId", 100, "devices", List.of())).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[SIM-11.01][AT-SIM-01.3][TC-SIM-091] 499대에서 2대 배치 → SIM_DEVICE_QUOTA_EXCEEDED, 생성 0(부분 생성 없음), 1대는 성공")
    void quota() {
        space(100, "CLASSROOM");
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 499; i++) {
            batch.add(Map.of("deviceId", 10_000 + i, "typeKey", "th-sensor", "spaceId", 100));
        }
        assertThat(post("/internal/sim/devices/batch-create", Map.of("devices", batch)).status()).isEqualTo(201);
        Result over = post("/internal/sim/devices/batch-create", Map.of("devices", List.of(
                Map.of("deviceId", 20_000, "typeKey", "th-sensor", "spaceId", 100),
                Map.of("deviceId", 20_001, "typeKey", "th-sensor", "spaceId", 100))));
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.code()).isEqualTo("SIM_DEVICE_QUOTA_EXCEEDED");
        assertThat(count("SELECT count(*) FROM data2flow_sim.devices")).isEqualTo(499);
        assertThat(post("/internal/sim/devices", Map.of("deviceId", 20_000, "typeKey", "th-sensor", "spaceId", 100)).status()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM data2flow_sim.devices")).isEqualTo(500);
    }

    @Test
    @DisplayName("[SIM-03.01][AT-SIM-01.2][TC-SIM-030] 에어컨 1대 배치 → Switch·Thermostat·FanSpeed, 초기 상태 OFF, 특성 출처 CATALOG")
    void actuatorPlacement() {
        space(100, "CLASSROOM");
        device(500, "aircon", 100, "에어컨");
        JsonNode d = get("/internal/sim/devices/500").response();
        assertThat(d.get("category").asString()).isEqualTo("ACTUATOR");
        JsonNode state = d.get("actuatorState").get("capabilities");
        assertThat(state.has("Switch") && state.has("Thermostat") && state.has("FanSpeed")).isTrue();
        assertThat(state.get("Switch").get("on").asBoolean()).isFalse();
        assertThat(d.get("properties").get(0).get("origin").asString()).isEqualTo("CATALOG");
        assertThat(d.get("externalId").asString()).isEqualTo("5a1d0000000001f4");
        JsonNode s = driver().get().uri("/internal/sim/devices/500/state").exchange((req, res) -> CODEC.mapper().readTree(res.getBody()))
                .get("response");
        assertThat(s.get("capabilities").get("Thermostat").get("mode").asString()).isEqualTo("off");
    }

    @Test
    @DisplayName("[SIM-09.02][AT-SIM-03.1][TC-SIM-095] 프로필: 바꾼 값만 저장, 연결 기기에 반영, 사용 중 삭제 → SIM_PROFILE_IN_USE, 범위 밖 → SIM_PROPERTY_OUT_OF_RANGE")
    void profiles() {
        space(100, "CLASSROOM");
        String typeId = typeId("aircon");
        Result created = post("/internal/sim/profiles", Map.of("name", "우리 강의실 에어컨", "typeId", typeId,
                "overrides", Map.of("coolingCapacityKw", 5.0)));
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String profileId = created.response().get("id").asString();
        JsonNode cooling = find(created.response().get("properties"), "coolingCapacityKw");
        assertThat(cooling.get("origin").asString()).isEqualTo("PROFILE");
        assertThat(post("/internal/sim/profiles", Map.of("name", "범위 밖", "typeId", typeId,
                "overrides", Map.of("coolingCapacityKw", 25))).code()).isEqualTo("SIM_PROPERTY_OUT_OF_RANGE");
        assertThat(post("/internal/sim/devices", Map.of("deviceId", 501, "typeKey", "aircon", "spaceId", 100, "profileId", profileId))
                .status()).isEqualTo(201);
        JsonNode dev = get("/internal/sim/devices/501").response();
        assertThat(find(dev.get("properties"), "coolingCapacityKw").get("value").asDouble()).isEqualTo(5.0);
        assertThat(delete("/internal/sim/profiles/" + profileId).code()).isEqualTo("SIM_PROFILE_IN_USE");
        assertThat(put("/internal/sim/profiles/" + profileId, Map.of("name", "우리 강의실 에어컨", "overrides",
                Map.of("coolingCapacityKw", 6.0), "baseVersion", 0)).status()).isEqualTo(200);
        assertThat(put("/internal/sim/profiles/" + profileId, Map.of("name", "x", "overrides", Map.of(), "baseVersion", 0)).code())
                .isEqualTo("VERSION_CONFLICT");
        assertThat(find(get("/internal/sim/devices/501").response().get("properties"), "coolingCapacityKw").get("value").asDouble())
                .isEqualTo(6.0);
        assertThat(get("/internal/sim/profiles").response()).hasSize(1);
    }

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.1][TC-SIM-020] 생성기 PATCH(평균 24 → 30)와 특성 되돌리기(null → 상위 값), 출력 경로 PLATFORM_MQTT는 거부")
    void patchDevice() {
        space(100, "CLASSROOM");
        device(600, "th-sensor", 100, "온습도");
        Map<String, Object> gen = Map.of("temperature", Map.of("source", "GENERATOR", "generator",
                Map.of("kind", "DIURNAL", "params", Map.of("mean", 24, "amplitude", 0, "peakHour", 15))));
        assertThat(patch("/internal/sim/devices/600", Map.of("metricSources", gen, "overrides", Map.of("temperatureAccuracy", 0.0)))
                .status()).isEqualTo(200);
        Map<String, Object> hot = Map.of("temperature", Map.of("source", "GENERATOR", "generator",
                Map.of("kind", "DIURNAL", "params", Map.of("mean", 30, "amplitude", 0, "peakHour", 15))));
        JsonNode after = patch("/internal/sim/devices/600", Map.of("metricSources", hot)).response();
        assertThat(after.get("metricSources").get("temperature").get("generator").get("params").get("mean").asInt()).isEqualTo(30);
        Map<String, Object> revert = new HashMap<>();
        revert.put("temperatureAccuracy", null);
        JsonNode reverted = patch("/internal/sim/devices/600", Map.of("overrides", revert)).response();
        assertThat(find(reverted.get("properties"), "temperatureAccuracy").get("origin").asString()).isEqualTo("CATALOG");
        assertThat(patch("/internal/sim/devices/600", Map.of("outputPath", "PLATFORM_MQTT")).code())
                .isEqualTo("SIM_PLATFORM_BROKER_UNAVAILABLE");
        assertThat(patch("/internal/sim/devices/600", Map.of("reportIntervalSec", 1)).code()).isEqualTo("SIM_PROPERTY_OUT_OF_RANGE");
        assertThat(patch("/internal/sim/devices/600", Map.of("metricSources", Map.of("nope", Map.of("source", "PHYSICS")))).status())
                .isEqualTo(400);
        assertThat(delete("/internal/sim/devices/600").status()).isEqualTo(204);
        assertThat(get("/internal/sim/devices/600").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[SIM-09.01][AT-SIM-01.5][TC-SIM-090] 카탈로그: 기본 유형(센서 15·장비 11)과 키트, 분류 필터, 내부 호출 표시 없으면 401")
    void catalog() {
        JsonNode all = get("/internal/sim/catalog").response();
        assertThat(all.get("types").size()).isEqualTo(26);
        assertThat(all.get("kits").size()).isEqualTo(2);
        assertThat(get("/internal/sim/catalog?category=ACTUATOR").response().get("types").size()).isEqualTo(11);
        Result noCaller = exchange(org.springframework.web.client.RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (q, s) -> {
                }).build().get().uri("/internal/sim/catalog?organizationId=1"));
        assertThat(noCaller.status()).isEqualTo(401);
        Result noOrg = exchange(driver().get().uri("/internal/sim/catalog"));
        assertThat(noOrg.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[SIM-01.02][AT-SIM-04.1][TC-SIM-003] 공간 물리: 프리셋 강의실 198㎥, 범위 밖 → SIM_PROPERTY_OUT_OF_RANGE, 기기가 있으면 삭제 SIM_SPACE_BUSY, 미리 보기")
    void spaces() {
        space(100, "CLASSROOM");
        JsonNode s = get("/internal/sim/spaces/100").response();
        assertThat(s.get("volumeM3").asDouble()).isEqualTo(198.0);
        Map<String, Object> bad = Map.of("preset", "CUSTOM", "physics", Map.of("preset", "CUSTOM", "areaM2", 0, "heightM", 3,
                "uValue", 9, "envelopeM2", 10, "solarGainFactor", 0.1));
        Result r = put("/internal/sim/spaces/101", bad);
        assertThat(r.code()).isEqualTo("SIM_PROPERTY_OUT_OF_RANGE");
        assertThat(r.body().toString()).contains("physics.areaM2").contains("physics.uValue");
        device(700, "th-sensor", 100, "온습도");
        assertThat(delete("/internal/sim/spaces/100").code()).isEqualTo("SIM_SPACE_BUSY");
        JsonNode preview = post("/internal/sim/preview", Map.of("spaceId", 100, "hours", 6,
                "condition", Map.of("occupancy", 30, "outdoorTemp", 33))).response();
        assertThat(preview.get("series").get("temperature").size()).isEqualTo(37);
        assertThat(preview.get("series").get("co2").get(36).get("v").asDouble()).isGreaterThan(1000);
        JsonNode gen = post("/internal/sim/preview", Map.of("hours", 24, "generator", Map.of("kind", "DIURNAL",
                "params", Map.of("mean", 24, "amplitude", 3, "peakHour", 15)))).response();
        assertThat(gen.get("series").get("value").size()).isEqualTo(145);
        assertThat(put("/internal/sim/spaces/100/sandbox", Map.of("sandbox", true)).response().get("sandbox").asBoolean()).isTrue();
        assertThat(get("/internal/sim/spaces").response()).hasSize(1);
        assertThat(get("/internal/sim/overview").response().get("usage").get("devices").asInt()).isEqualTo(1);
    }

    private String typeId(String key) {
        for (JsonNode t : get("/internal/sim/catalog").response().get("types")) {
            if (t.get("key").asString().equals(key)) {
                return t.get("id").asString();
            }
        }
        throw new IllegalStateException(key);
    }

    private static JsonNode find(JsonNode properties, String key) {
        for (JsonNode p : properties) {
            if (p.get("key").asString().equals(key)) {
                return p;
            }
        }
        throw new IllegalStateException(key);
    }
}
