package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.sensor.domain.BatteryModel;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class BatteryModelTest {

    @Test
    @DisplayName("[SIM-02.05][AT-SIM-05.4][TC-SIM-025] 보고당 0.01%p, 1,000회 → 100에서 90.0(±0.001), 0 아래로 안 내려감")
    void drains() {
        double b = 100;
        for (int i = 0; i < 1000; i++) {
            b = BatteryModel.afterReport(b, 0.01);
        }
        assertThat(b).isCloseTo(90.0, offset(0.001));
        assertThat(BatteryModel.afterReport(0.005, 0.01)).isZero();
        assertThat(BatteryModel.drainPerReport(2, 60)).isCloseTo(100.0 / (2 * 365 * 1440), offset(1e-12));
        assertThat(BatteryModel.drainPerReport(0, 60)).isZero();
    }

    @Test
    @DisplayName("[SIM-02.05][AT-SIM-05.4][TC-SIM-025] 배터리가 0이 되면 보고를 멈춘다")
    void stopsWhenEmpty() {
        Worlds w = Worlds.classroom().hours(1)
                .device(Worlds.dev(1, "온습도", "th-sensor").interval(60).battery(0.05).drain(0.01).build());
        SimulationWorld world = w.world();
        Worlds.runToEnd(world);
        long reports = world.readings().stream().filter(r -> r.metric().equals("temperature")).count();
        assertThat(reports).isEqualTo(5);
        assertThat(world.sensorRuntime(1).battery).isZero();
    }
}
