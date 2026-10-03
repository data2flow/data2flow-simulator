package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricSourceResolverTest {

    @Test
    @DisplayName("[SIM-02.02][AT-SIM-06.1][TC-SIM-016] 물리 연동 센서는 에어컨 ON 뒤 값이 내려가고, 생성기 센서는 장비와 무관, 항목별 출처 혼합 가능")
    void sources() {
        Worlds w = Worlds.classroom().hours(3).outdoor(OutdoorSpec.constant(33, 60))
                .device(Worlds.dev(1, "물리", "th-sensor").interval(60).prop("temperatureAccuracy", 0.0).build())
                .device(Worlds.dev(2, "생성기", "th-sensor").interval(60)
                        .source("temperature", new MetricSource("GENERATOR", GeneratorSpec.fixed(21.0)))
                        .source("battery", new MetricSource("GENERATOR", GeneratorSpec.fixed(77))).build())
                .device(Worlds.dev(10, "에어컨", "aircon").prop("reactionDelaySec", 0).build());
        SimulationWorld world = w.world();
        for (int i = 0; i < 360; i++) {
            world.step(Instant.EPOCH);
        }
        WorldState.InboundCommand c = new WorldState.InboundCommand();
        c.commandId = "on";
        c.deviceId = 10;
        c.capability = "Thermostat";
        c.command = "set";
        c.args.put("mode", "cool");
        c.args.put("targetTemperature", 22);
        world.submit(c);
        Worlds.runToEnd(world);
        Instant on = Worlds.START.plusSeconds(3600);
        List<Double> physicsBefore = values(world, "물리", "temperature", Worlds.START.plusSeconds(3000), on);
        List<Double> physicsAfter = values(world, "물리", "temperature", on.plusSeconds(5400), on.plusSeconds(7200));
        assertThat(physicsAfter.getLast()).isLessThan(physicsBefore.getLast() - 2);
        assertThat(values(world, "생성기", "temperature", Worlds.START, Worlds.START.plusSeconds(4 * 3600))).allMatch(v -> v == 21.0);
        assertThat(values(world, "생성기", "battery", Worlds.START, Worlds.START.plusSeconds(4 * 3600))).allMatch(v -> v == 77.0);
        assertThat(values(world, "생성기", "humidity", Worlds.START, Worlds.START.plusSeconds(4 * 3600)).stream().distinct().count())
                .isGreaterThan(1);
    }

    static List<Double> values(SimulationWorld w, String device, String metric, Instant from, Instant to) {
        return w.readings().stream().filter(r -> r.device().equals(device) && r.metric().equals(metric))
                .filter(r -> !r.simTime().isBefore(from) && r.simTime().isBefore(to)).map(SimulationWorld.Reading::value).toList();
    }
}
