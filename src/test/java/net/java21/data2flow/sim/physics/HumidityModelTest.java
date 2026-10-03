package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.Psychrometrics;
import net.java21.data2flow.sim.support.PhysicsHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HumidityModelTest {

    @Test
    @DisplayName("[SIM-01.05][AT-SIM-04.6][TC-SIM-011] 냉방 1시간 → 상대 습도 65%에서 60% 미만으로")
    void coolingDehumidifies() {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(27, 60))
                .state(s -> {
                    s.temperature = 27;
                    s.absHumidity = Psychrometrics.absoluteHumidity(27, 65);
                })
                .effects(PhysicsHarness.cooling(3.5, 24));
        assertThat(h.state().relativeHumidity()).isCloseTo(65, org.assertj.core.data.Offset.offset(0.01));
        double rh = h.run(3600, s -> s.relativeHumidity()).getLast();
        assertThat(rh).isLessThan(60);
    }

    @Test
    @DisplayName("[SIM-01.05][AT-SIM-04.6][TC-SIM-011] 재실 30명·장비 OFF → 증가, 가습기 ON → 증가, 0~100% 고정")
    void occupantsAndHumidifierRaise() {
        PhysicsHarness people = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> {
                    s.temperature = 25;
                    s.absHumidity = Psychrometrics.absoluteHumidity(25, 50);
                    s.occupancy = 30;
                });
        assertThat(people.run(3600, s -> s.absHumidity).getLast()).isGreaterThan(Psychrometrics.absoluteHumidity(25, 50));

        PhysicsHarness humid = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 40))
                .state(s -> {
                    s.temperature = 25;
                    s.absHumidity = Psychrometrics.absoluteHumidity(25, 40);
                })
                .effects(e -> {
                    e.humidifyGph = 300;
                    e.humidifyTarget = 100;
                });
        assertThat(humid.run(3600, s -> s.relativeHumidity()).getLast()).isGreaterThan(40);

        PhysicsHarness flood = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(20, 100))
                .state(s -> s.occupancy = 300)
                .effects(e -> {
                    e.humidifyGph = 30_000;
                    e.humidifyTarget = 100;
                });
        List<Double> rh = flood.run(6 * 3600, s -> s.relativeHumidity());
        assertThat(rh).allMatch(v -> v >= 0 && v <= 100);
    }

    @Test
    @DisplayName("[SIM-01.05][AT-SIM-04.6][TC-SIM-011] 외기 습도가 높을수록 환기할 때 실내 습도가 더 오른다")
    void outdoorHumidityMatters() {
        double dry = ventilated(40);
        double wet = ventilated(90);
        assertThat(wet).isGreaterThan(dry);
    }

    private static double ventilated(double outdoorRh) {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, outdoorRh))
                .state(s -> {
                    s.temperature = 25;
                    s.absHumidity = Psychrometrics.absoluteHumidity(25, 50);
                })
                .effects(e -> e.ventilationM3h = 500);
        return h.run(3600, s -> s.relativeHumidity()).getLast();
    }
}
