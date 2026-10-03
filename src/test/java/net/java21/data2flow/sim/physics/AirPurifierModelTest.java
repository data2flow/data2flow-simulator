package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.Psychrometrics;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AirPurifierModelTest {

    @Test
    @DisplayName("[SIM-09.04][AT-SIM-06.3][TC-SIM-100] CADR 300 vs 600, PM2.5 80 → 15μg/㎥ 감소 속도 비 2.0±20%")
    void cadrDoublesRate() {
        double t300 = pmTime(300);
        double t600 = pmTime(600);
        assertThat(t300 / t600).isBetween(PhysicsTolerances.CADR_RATE_RATIO_MIN, PhysicsTolerances.CADR_RATE_RATIO_MAX);
    }

    private static double pmTime(double cadr) {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(new OutdoorSpec("DIURNAL",
                        new OutdoorSpec.Diurnal(25, 25, 15, 50, 0.0, 5.0), null, null))
                .state(s -> s.pm25 = 80).effects(e -> e.cadrM3h = cadr);
        return h.secondsUntil(24 * 3600, s -> s.pm25 <= 15);
    }

    @Test
    @DisplayName("[SIM-09.04][AT-SIM-06.3][TC-SIM-100] 가습량 2배 → 습도 변화율 2배 ±20%")
    void humidifierDoubles() {
        double r1 = humidRate(300);
        double r2 = humidRate(600);
        assertThat(r2 / r1).isBetween(1.6, 2.4);
    }

    private static double humidRate(double gph) {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 40))
                .state(s -> {
                    s.temperature = 25;
                    s.absHumidity = Psychrometrics.absoluteHumidity(25, 40);
                })
                .effects(e -> {
                    e.humidifyGph = gph;
                    e.humidifyTarget = 100;
                });
        double start = h.state().absHumidity;
        return h.run(600, s -> s.absHumidity).getLast() - start;
    }
}
