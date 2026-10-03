package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhysicsModelTest {

    @Test
    @DisplayName("[SIM-09.04][AT-SIM-06.2][TC-SIM-099] 냉방 능력 3.5kW vs 7.0kW → 목표 도달 시간 비 0.5±20%")
    void doubleCapacityHalvesTime() {
        double t1 = timeToTarget(3.5);
        double t2 = timeToTarget(7.0);
        assertThat(t1).isPositive();
        assertThat(t2 / t1).isBetween(PhysicsTolerances.CAPACITY_TIME_RATIO_MIN, PhysicsTolerances.CAPACITY_TIME_RATIO_MAX);
    }

    private static double timeToTarget(double kw) {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(33, 60))
                .state(s -> s.temperature = 30).effects(PhysicsHarness.cooling(kw, 24));
        return h.secondsUntil(6 * 3600, s -> s.temperature <= 24.5);
    }
}
