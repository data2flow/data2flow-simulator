package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Co2VentilationTest {

    @Test
    @DisplayName("[SIM-01.04][AT-SIM-04.3][TC-SIM-010] 1,200ppm·30명에서 환기 level 3 ON → 다음 5분 기울기 음수")
    void ventilationTurnsTrendDown() {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> {
                    s.co2 = 1200;
                    s.occupancy = 30;
                })
                .effects(e -> {
                    e.ventilationM3h = 250 * 3;
                    e.heatRecovery = 0.7;
                });
        List<Double> co2 = h.run(300, s -> s.co2);
        assertThat(co2.getLast()).isLessThan(1200);
        for (int i = 1; i < co2.size(); i++) {
            assertThat(co2.get(i)).isLessThanOrEqualTo(co2.get(i - 1));
        }
    }

    @Test
    @DisplayName("[SIM-01.04][AT-SIM-04.3][TC-SIM-010] 창문 열림은 감소를 더 빠르게, 재실 0·환기 최대 3시간 뒤 420±30, 420 아래로는 안 내려감")
    void decaysToOutdoor() {
        PhysicsHarness closed = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50)).state(s -> s.co2 = 1200);
        PhysicsHarness open = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50)).state(s -> {
            s.co2 = 1200;
            s.windowOpen = true;
        });
        assertThat(open.run(600, s -> s.co2).getLast()).isLessThan(closed.run(600, s -> s.co2).getLast());

        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> s.co2 = 1200)
                .effects(e -> e.ventilationM3h = 750);
        List<Double> co2 = h.run(3 * 3600, s -> s.co2);
        assertThat(co2.getLast()).isBetween(PhysicsTolerances.OUTDOOR_CO2 - PhysicsTolerances.OUTDOOR_CO2_BAND,
                PhysicsTolerances.OUTDOOR_CO2 + PhysicsTolerances.OUTDOOR_CO2_BAND);
        assertThat(co2).allMatch(v -> v >= 420 - 1e-9);
    }
}
