package net.java21.data2flow.sim.it;

import net.java21.data2flow.sim.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** action virtual 드라이버 계약의 simulator 쪽(API-SIM-30·32, EVT-SIM-03 = EVT-ACT-06·07) */
class CommandIT extends IntegrationTestSupport {

    static Map<String, Object> cmd(String id, String capability, String command, Map<String, Object> args) {
        return Map.of("commandId", id, "capability", capability, "command", command, "args", args);
    }

    @Test
    @DisplayName("[SIM-03.04][AT-SIM-06.4][TC-SIM-031] 상시 환경 장비: 명령 202 → 다음 틱에 ack·reported(ACT 모양), 같은 commandId 재전송은 한 번만 적용")
    void ambientCommand() {
        space(1, "CLASSROOM");
        device(15, "aircon", 1, "에어컨");
        rounds(1, Duration.ofSeconds(10));   // 상시 환경 시작
        Result r = post(driver(), "/internal/sim/devices/15/commands", cmd("c-1", "Switch", "set", Map.of("on", true)));
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.response().get("applied").asBoolean()).isFalse();
        rounds(2, Duration.ofSeconds(10));
        post(driver(), "/internal/sim/devices/15/commands", cmd("c-1", "Switch", "set", Map.of("on", true)));
        rounds(2, Duration.ofSeconds(10));
        List<JsonNode> acks = events("device.command.ack");
        assertThat(acks).hasSize(2).allSatisfy(a -> {
            assertThat(a.get("v").asInt()).isEqualTo(1);
            assertThat(a.get("messageId").asString()).isNotBlank();
            assertThat(a.get("payload").get("commandId").asString()).isEqualTo("c-1");
            assertThat(a.get("payload").get("deviceId").asLong()).isEqualTo(15);
            assertThat(a.get("payload").get("result").asString()).isEqualTo("ACKED");
        });
        List<JsonNode> reported = events("device.state.reported");
        assertThat(reported).hasSize(2);
        assertThat(reported.get(0).get("payload").get("version").asLong()).isEqualTo(1);
        assertThat(reported.get(1).get("payload").get("version").asLong()).isEqualTo(1);
        assertThat(reported.get(0).get("payload").get("capabilities").get("Thermostat").get("mode").asString()).isEqualTo("cool");
        JsonNode state = exchange(driver().get().uri("/internal/sim/devices/15/state")).response();
        assertThat(state.get("version").asLong()).isEqualTo(1);
        assertThat(state.get("capabilities").get("Thermostat").has("currentTemperature")).isTrue();
        assertThat(count("SELECT count(*) FROM data2flow_sim.device_commands WHERE status = 'TAKEN'")).isEqualTo(2);
    }

    @Test
    @DisplayName("[SIM-03.02][AT-SIM-01.2][TC-SIM-032] 범위 밖(설정 온도 40℃)·지원하지 않는 기능 400, 없는 기기 404, 센서 400")
    void validation() {
        space(1, "CLASSROOM");
        device(15, "aircon", 1, "에어컨");
        device(11, "th-sensor", 1, "온습도");
        Result range = post(driver(), "/internal/sim/devices/15/commands", cmd("c-2", "Thermostat", "set", Map.of("targetTemperature", 40)));
        assertThat(range.status()).isEqualTo(400);
        assertThat(range.body().get("errors").get(0).get("field").asString()).isEqualTo("args.targetTemperature");
        assertThat(post(driver(), "/internal/sim/devices/15/commands", cmd("c-3", "Dimmer", "set", Map.of("level", 3))).status()).isEqualTo(400);
        assertThat(post(driver(), "/internal/sim/devices/999/commands", cmd("c-4", "Switch", "on", Map.of())).status()).isEqualTo(404);
        assertThat(post(driver(), "/internal/sim/devices/11/commands", cmd("c-5", "Switch", "on", Map.of())).status()).isEqualTo(400);
        assertThat(post(driver(), "/internal/sim/devices/15/commands", Map.of("capability", "Switch")).status()).isEqualTo(400);
        assertThat(exchange(driver().get().uri("/internal/sim/devices/11/state")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[SIM-03.04][AT-SIM-06.4][TC-SIM-036] 돌고 있는 세계가 없는 장비(RUN_ONLY, 실행 없음)는 바로 적용하고 ack·reported")
    void directApply() {
        space(1, "CLASSROOM");
        post("/internal/sim/devices", Map.of("deviceId", 16, "typeKey", "light", "spaceId", 1, "reportMode", "RUN_ONLY"));
        Result r = post(driver(), "/internal/sim/devices/16/commands", cmd("c-9", "Dimmer", "setLevel", Map.of("value", 60)));
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.response().get("applied").asBoolean()).isTrue();
        assertThat(events("device.command.ack")).hasSize(1);
        JsonNode state = exchange(driver().get().uri("/internal/sim/devices/16/state")).response();
        assertThat(state.get("capabilities").get("Dimmer").get("level").asInt()).isEqualTo(60);
        assertThat(state.get("capabilities").get("Switch").get("on").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("[SIM-03.04][AT-SIM-06.4][TC-SIM-036] 실패 확률 100%면 ack도 상태 보고도 없다(action 타임아웃·재시도로 회복)")
    void failure() {
        space(1, "CLASSROOM");
        post("/internal/sim/devices", Map.of("deviceId", 15, "typeKey", "aircon", "spaceId", 1, "response", Map.of("failurePct", 100)));
        rounds(1, Duration.ofSeconds(10));
        post(driver(), "/internal/sim/devices/15/commands", cmd("c-x", "Switch", "on", Map.of()));
        rounds(3, Duration.ofSeconds(10));
        assertThat(events("device.command.ack")).isEmpty();
        assertThat(events("device.state.reported")).isEmpty();
    }
}
