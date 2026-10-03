package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class Co2ModelTest {

    @Test
    @DisplayName("[SIM-01.04][AT-SIM-04.2][TC-SIM-009] 강의실 30명·환기 OFF·450ppm → 60분 안에 1,000ppm 초과, 증가율은 인원에 비례")
    void co2RisesWithOccupancy() {
        PhysicsHarness h30 = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> {
                    s.co2 = 450;
                    s.occupancy = 30;
                });
        double seconds = h30.secondsUntil(2 * 3600, s -> s.co2 > 1000);
        assertThat(seconds).isPositive().isLessThanOrEqualTo(PhysicsTolerances.CO2_LIMIT_MINUTES * 60);

        double rate30 = initialRate(30);
        double rate15 = initialRate(15);
        assertThat(rate15 / rate30).isCloseTo(0.5, offset(0.05));
    }

    private static double initialRate(int people) {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> {
                    s.co2 = 420;
                    s.occupancy = people;
                });
        double end = h.run(300, s -> s.co2).getLast();
        return (end - 420) / 300;
    }
}
