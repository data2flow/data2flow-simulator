package net.java21.data2flow.sim.run;

import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectationEvaluatorTest {

    static Worlds world(int commandAtMin) {
        Instant start = Worlds.START;
        return Worlds.classroom().hours(1).outdoor(OutdoorSpec.constant(33, 60))
                .device(Worlds.dev(10, "에어컨", "aircon").build())
                .event(new ScenarioEvent("on", "ACTUATOR", start.plusSeconds(commandAtMin * 60L), null, Map.of("deviceId", 10),
                        Map.of("capability", "Switch", "command", "on")))
                .expectation(new Expectation("ac-10m", "DEVICE_STATE_REACHED", Map.of("deviceId", 10), Map.of("power", "ON"),
                        start.plusSeconds(600)))
                .expectation(new Expectation("alarm", "ALARM_COUNT", Map.of(), Map.of("op", "==", "value", 1), null))
                .expectation(new Expectation("comfort", "METRIC_RANGE_RATIO", Map.of("spaceId", Worlds.SPACE, "metric", "temperature"),
                        Map.of("min", 0, "max", 40, "ratio", 0.9), null))
                .expectation(new Expectation("cycles", "CONTROL_COUNT_MAX", Map.of("deviceId", 10), Map.of("max", 0), null))
                .expectation(new Expectation("mode", "DEVICE_STATE_REACHED", Map.of("deviceId", 10),
                        Map.of("Thermostat", Map.of("mode", "cool")), null));
    }

    @Test
    @DisplayName("[SIM-04.06][AT-SIM-10.1][TC-SIM-057] \"10분 안에 에어컨 ON\": 7분 → 통과(근거 +7m), 11분 → 실패, 알람은 SKIPPED, 비율·제어 횟수 판정")
    void evaluate() {
        SimulationWorld ok = world(7).world();
        Worlds.runToEnd(ok);
        ok.finalizeExpectations(false);
        Map<String, WorldState.ExpectationProgress> e = ok.expectations();
        assertThat(e.get("ac-10m").state).isEqualTo("PASSED");
        assertThat(Instant.ofEpochMilli(e.get("ac-10m").atMs)).isEqualTo(Worlds.START.plusSeconds(7 * 60 + 10));
        assertThat(e.get("alarm").state).isEqualTo("SKIPPED");
        assertThat(e.get("comfort").state).isEqualTo("PASSED");
        assertThat(e.get("cycles").state).isEqualTo("FAILED");
        assertThat(e.get("mode").state).isEqualTo("PASSED");

        SimulationWorld late = world(11).world();
        Worlds.runToEnd(late);
        late.finalizeExpectations(false);
        assertThat(late.expectations().get("ac-10m").state).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("[SIM-04.06][AT-SIM-10.2][TC-SIM-057] 정지(partial) 실행은 끝난 구간까지만 판정, 기한이 안 온 항목은 SKIPPED")
    void partial() {
        SimulationWorld w = world(30).world();
        for (int i = 0; i < 30; i++) {
            w.step(Instant.EPOCH);
        }
        w.finalizeExpectations(true);
        assertThat(w.expectations().get("ac-10m").state).isEqualTo("SKIPPED");
        assertThat(w.expectations().get("mode").state).isEqualTo("SKIPPED");
        assertThat(w.expectations().get("comfort").state).isEqualTo("PASSED");
        assertThat(w.expectations().get("cycles").state).isEqualTo("PASSED");
    }
}
