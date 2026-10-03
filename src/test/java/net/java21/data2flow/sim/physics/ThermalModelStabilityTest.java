package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ThermalModelStabilityTest {

    @ParameterizedTest
    @ValueSource(doubles = {-20, 33, 50})
    @DisplayName("[SIM-01.03][AT-SIM-04.1][TC-SIM-008] 틱 1초와 10초의 1시간 뒤 차이 < 0.2℃, 극단 외기에서 NaN·발산 없음")
    void tickLengthIndependent(double outdoor) {
        PhysicsHarness fine = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(outdoor, 50)).tickSec(1)
                .state(s -> s.occupancy = 25).effects(PhysicsHarness.cooling(3.5, 24));
        PhysicsHarness coarse = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(outdoor, 50)).tickSec(10)
                .state(s -> s.occupancy = 25).effects(PhysicsHarness.cooling(3.5, 24));
        double a = fine.run(3600, s -> s.temperature).getLast();
        double b = coarse.run(3600, s -> s.temperature).getLast();
        assertThat(Math.abs(a - b)).isLessThan(0.2);
        assertThat(Double.isFinite(a) && Double.isFinite(fine.state().co2) && Double.isFinite(fine.state().absHumidity)).isTrue();
        assertThat(fine.state().relativeHumidity()).isBetween(0.0, 100.0);
    }
}
