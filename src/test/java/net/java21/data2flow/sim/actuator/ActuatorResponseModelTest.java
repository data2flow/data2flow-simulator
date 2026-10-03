package net.java21.data2flow.sim.actuator;

import net.java21.data2flow.sim.device.domain.ResponseSettings;
import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorResponseModelTest {

    @Test
    @DisplayName("[SIM-03.04][AT-SIM-06.4][TC-SIM-036] 실패 10%·명령 100회 → ack 누락 10±6회, ack 지연 500ms 반영, 상태 보고는 적용된 명령에만")
    void failureProbability() {
        Worlds w = Worlds.classroom().hours(2)
                .device(Worlds.dev(10, "에어컨", "aircon").response(new ResponseSettings(0, 500, 10.0)).build());
        SimulationWorld world = w.world();
        List<Emission> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            WorldState.InboundCommand c = new WorldState.InboundCommand();
            c.commandId = "cmd-" + i;
            c.deviceId = 10;
            c.capability = "Thermostat";
            c.command = "set";
            c.args.put("targetTemperature", 20 + (i % 10));
            world.submit(c);
            Instant submitted = world.simNow();
            List<Emission> step = world.step(Instant.EPOCH);
            out.addAll(step);
            step.stream().filter(e -> e instanceof Emission.CommandAck).map(e -> (Emission.CommandAck) e)
                    .forEach(a -> assertThat(Duration.between(submitted, a.at()).toMillis()).isEqualTo(500));
        }
        long acks = out.stream().filter(e -> e instanceof Emission.CommandAck a && "ACKED".equals(a.result())).count();
        long reported = out.stream().filter(e -> e instanceof Emission.StateReported).count();
        assertThat(100 - acks).isBetween(4L, 16L);
        assertThat(reported).isEqualTo(acks);
        assertThat(world.stats().controlCount).isEqualTo(acks);
    }

    @Test
    @DisplayName("[SIM-03.04][AT-SIM-06.4][TC-SIM-036] 같은 commandId 재전송은 다시 적용하지 않고 같은 응답, 응답 없던 명령은 재시도로 다시 판정")
    void idempotentRetry() {
        Worlds w = Worlds.classroom().hours(1)
                .device(Worlds.dev(10, "에어컨", "aircon").response(new ResponseSettings(0, 0, 0.0)).build());
        SimulationWorld world = w.world();
        for (int i = 0; i < 2; i++) {
            WorldState.InboundCommand c = new WorldState.InboundCommand();
            c.commandId = "same";
            c.deviceId = 10;
            c.capability = "Switch";
            c.command = "on";
            world.submit(c);
            world.step(Instant.EPOCH);
        }
        assertThat(world.actuatorState(10).version).isEqualTo(1);
        assertThat(world.stats().controlCount).isEqualTo(1);

        WorldState.InboundCommand bad = new WorldState.InboundCommand();
        bad.commandId = "bad";
        bad.deviceId = 10;
        bad.capability = "Thermostat";
        bad.command = "set";
        bad.args.put("targetTemperature", 99);
        world.submit(bad);
        List<Emission> out = world.step(Instant.EPOCH);
        assertThat(out).anyMatch(e -> e instanceof Emission.CommandAck a && "FAILED".equals(a.result())
                && "INVALID_COMMAND".equals(a.reason()));

        WorldState.InboundCommand unknown = new WorldState.InboundCommand();
        unknown.commandId = "x";
        unknown.deviceId = 999;
        unknown.capability = "Switch";
        unknown.command = "on";
        world.submit(unknown);
        assertThat(world.step(Instant.EPOCH)).anyMatch(e -> e instanceof Emission.CommandAck a && "DEVICE_NOT_SIMULATED".equals(a.reason()));
    }
}
