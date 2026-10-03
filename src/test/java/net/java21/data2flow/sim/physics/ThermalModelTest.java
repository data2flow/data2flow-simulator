package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThermalModelTest {

    @Test
    @DisplayName("[SIM-01.03][AT-SIM-04.1][TC-SIM-006] 외기 33℃·장비 OFF·초기 26℃ → 6시간 동안 단조 증가, 외기를 넘지 않음, 재실 30명이면 더 빨리 오름")
    void temperatureRisesMonotonically() {
        PhysicsHarness empty = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(33, 60))
                .state(s -> s.temperature = 26);
        List<Double> temps = empty.run(6 * 3600, s -> s.temperature);
        double prev = 26;
        int decreasing = 0;
        for (double t : temps) {
            if (t < prev) {
                decreasing++;
            }
            prev = t;
        }
        assertThat(decreasing).isZero();
        assertThat(temps.getLast()).isLessThan(33).isGreaterThan(26);

        PhysicsHarness occupied = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(33, 60))
                .state(s -> {
                    s.temperature = 26;
                    s.occupancy = 30;
                });
        double afterHourOccupied = occupied.run(3600, s -> s.temperature).getLast();
        double afterHourEmpty = temps.get(3599);
        assertThat(afterHourOccupied).isGreaterThan(afterHourEmpty);
    }
}
