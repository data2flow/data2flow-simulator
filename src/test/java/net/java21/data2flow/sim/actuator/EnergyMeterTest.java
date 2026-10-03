package net.java21.data2flow.sim.actuator;

import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class EnergyMeterTest {

    static SimulationWorld fullLoad(int tickSec) {
        Worlds w = Worlds.classroom().tickSec(tickSec).hours(1).outdoor(OutdoorSpec.constant(38, 60))
                .device(Worlds.dev(10, "에어컨", "aircon").prop("reactionDelaySec", 0).interval(300).build());
        SimulationWorld world = w.world();
        WorldState.InboundCommand c = new WorldState.InboundCommand();
        c.commandId = "on";
        c.deviceId = 10;
        c.capability = "Thermostat";
        c.command = "set";
        c.args.put("mode", "cool");
        c.args.put("targetTemperature", 18);
        c.receivedAtMs = Worlds.START.toEpochMilli();
        world.submit(c);
        return world;
    }

    @Test
    @DisplayName("[SIM-03.05][AT-SIM-06.5][TC-SIM-038] 소비 전력 1.2kW로 1시간 ON(최대 부하) → 1.2kWh ±2%, 틱 길이(가속)와 무관")
    void energy() {
        SimulationWorld a = fullLoad(10);
        Worlds.runToEnd(a);
        double kwh = a.actuatorRuntime(10).energyKwh;
        assertThat(kwh).isCloseTo(1.2, offset(1.2 * PhysicsTolerances.ENERGY_REL));
        SimulationWorld b = fullLoad(1);
        Worlds.runToEnd(b);
        assertThat(b.actuatorRuntime(10).energyKwh).isCloseTo(kwh, offset(1.2 * PhysicsTolerances.ENERGY_REL));
        assertThat(a.stats().energyKwh).isCloseTo(kwh, offset(1e-9));
    }

    @Test
    @DisplayName("[SIM-03.05][AT-SIM-06.5][TC-SIM-038] OFF면 대기 전력만, 장비 보고에 power·energy 측정 항목이 들어간다")
    void standbyAndReport() {
        Worlds w = Worlds.classroom().hours(1).device(Worlds.dev(10, "에어컨", "aircon").interval(300).build());
        SimulationWorld world = w.world();
        var out = Worlds.runToEnd(world);
        assertThat(world.actuatorRuntime(10).energyKwh).isCloseTo(0.002, offset(1e-6));   // 2W × 1h
        assertThat(Worlds.uplinks(out)).isNotEmpty();
        assertThat(world.readings()).anyMatch(r -> r.metric().equals("power")).anyMatch(r -> r.metric().equals("energy"));
        assertThat(out).noneMatch(e -> e instanceof Emission.CommandAck);
        assertThat(world.step(Instant.EPOCH)).isNotNull();
    }
}
