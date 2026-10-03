package net.java21.data2flow.sim.actuator;

import net.java21.data2flow.sim.actuator.domain.ActuatorPhysics;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.actuator.domain.CommandInterpreter;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.physics.domain.ActuatorEffects;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorFeedbackTest {

    static final Map<String, Object> PROPS = PropertyResolver.effective(BuiltinCatalog.type("aircon").orElseThrow(), Map.of(), Map.of());

    static ActuatorState aircon(Map<String, Object> thermostat, int fan) {
        ActuatorState s = ActuatorState.initial(BuiltinCatalog.type("aircon").orElseThrow().capabilities());
        s = CommandInterpreter.apply("aircon", PROPS, s, "Thermostat", "set", thermostat);
        return CommandInterpreter.apply("aircon", PROPS, s, "FanSpeed", "set", Map.of("level", fan));
    }

    @Test
    @DisplayName("[SIM-03.03][AT-SIM-06.1][TC-SIM-033] 전원·모드·설정 온도·풍량이 열 출력에 반영되고, 풍량 high가 low보다 빨리 수렴, OFF면 출력 0")
    void feedback() {
        double high = timeTo24(aircon(Map.of("mode", "cool", "targetTemperature", 24), 3));
        double low = timeTo24(aircon(Map.of("mode", "cool", "targetTemperature", 24), 1));
        assertThat(high).isPositive().isLessThan(low);

        ActuatorEffects e = new ActuatorEffects();
        ActuatorPhysics.addEffects("aircon", PROPS, CommandInterpreter.apply("aircon", PROPS,
                aircon(Map.of("mode", "cool"), 3), "Switch", "off", Map.of()), e);
        assertThat(e.hvacOn()).isFalse();
        assertThat(e.coolingCapacityW).isZero();

        ActuatorEffects heat = new ActuatorEffects();
        ActuatorPhysics.addEffects("aircon", PROPS, aircon(Map.of("mode", "heat", "targetTemperature", 22), 2), heat);
        assertThat(heat.hvacMode).isEqualTo(ActuatorEffects.HvacMode.HEAT);
        assertThat(heat.setpoint).isEqualTo(22);
        assertThat(heat.heatingCapacityW).isEqualTo(4000 * 0.8);
    }

    private static double timeTo24(ActuatorState s) {
        ActuatorEffects e = new ActuatorEffects();
        ActuatorPhysics.addEffects("aircon", PROPS, s, e);
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(33, 60)).state(x -> x.temperature = 30)
                .effects(x -> {
                    x.hvacMode = e.hvacMode;
                    x.coolingCapacityW = e.coolingCapacityW;
                    x.setpoint = e.setpoint;
                });
        return h.secondsUntil(6 * 3600, x -> x.temperature <= 24.5);
    }

    @Test
    @DisplayName("[SIM-03.03][AT-SIM-06.1][TC-SIM-033] 명령은 반응 지연(2분) 뒤 물리에 반영되고, 그 뒤 온도가 내려간다")
    void reactionDelayInWorld() {
        Worlds w = Worlds.classroom().hours(2).outdoor(OutdoorSpec.constant(33, 60))
                .device(Worlds.dev(10, "에어컨", "aircon").build());
        SimulationWorld world = w.world();
        for (int i = 0; i < 6; i++) {
            world.step(Instant.EPOCH);
        }
        double before = world.space(Worlds.SPACE).temperature;
        WorldState.InboundCommand c = new WorldState.InboundCommand();
        c.commandId = "c-1";
        c.deviceId = 10;
        c.capability = "Thermostat";
        c.command = "set";
        c.args.put("mode", "cool");
        c.args.put("targetTemperature", 24);
        world.submit(c);
        for (int i = 0; i < 12; i++) {   // 2분 = 12틱: 아직 반영 전
            world.step(Instant.EPOCH);
        }
        assertThat(world.actuatorState(10).on()).isTrue();
        assertThat(world.actuatorRuntime(10).effective.on()).isFalse();
        assertThat(world.space(Worlds.SPACE).temperature).isGreaterThan(before);
        for (int i = 0; i < 60; i++) {
            world.step(Instant.EPOCH);
        }
        assertThat(world.actuatorRuntime(10).effective.on()).isTrue();
        assertThat(world.space(Worlds.SPACE).temperature).isLessThan(before);
    }
}
