package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;
import net.java21.data2flow.sim.sensor.domain.GeneratorState;
import net.java21.data2flow.sim.sensor.domain.Generators;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventGeneratorTest {

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.2][TC-SIM-018] 문 열림 시간당 2회·시드 7·하루 → 48±14회, 0→1→0 순서")
    void poissonEvents() {
        GeneratorSpec spec = GeneratorSpec.event(2, 60, 1, 0);
        GeneratorState s = new GeneratorState();
        Instant t = Instant.parse("2026-08-10T00:00:00Z");
        ZoneId zone = ZoneId.of("Asia/Seoul");
        double prev = 0;
        int rises = 0;
        for (int tick = 0; tick < 8640; tick++) {
            Instant end = t.plusSeconds((tick + 1) * 10L);
            Generators.tick(spec, s, 7, "문", "door", tick, end, 10);
            double v = Generators.sample(spec, s, end, zone, 7, "문", "door", tick);
            assertThat(v).isIn(0.0, 1.0);
            if (prev == 0 && v == 1) {
                rises++;
            }
            prev = v;
        }
        assertThat(s.events).isBetween(34L, 62L);
        assertThat(rises).isEqualTo((int) s.events);
    }

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.2][TC-SIM-018] 이벤트 센서는 상태가 바뀔 때만 보고한다(같은 값 연속 보고 0)")
    void reportsOnChangeOnly() {
        Worlds w = Worlds.classroom().hours(24)
                .device(Worlds.dev(1, "문", "door-sensor").prop("heartbeatSec", 86_400)
                        .source("door", new MetricSource("GENERATOR", GeneratorSpec.event(2, 60, 1, 0))).build());
        SimulationWorld world = w.world();
        Worlds.runToEnd(world);
        List<Double> doors = world.readings().stream().filter(r -> r.metric().equals("door")).map(SimulationWorld.Reading::value).toList();
        assertThat(doors.size()).isGreaterThan(20);
        for (int i = 1; i < doors.size(); i++) {
            assertThat(doors.get(i)).isNotEqualTo(doors.get(i - 1));
        }
    }
}
