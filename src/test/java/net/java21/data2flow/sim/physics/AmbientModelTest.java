package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.SolarModel;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AmbientModelTest {

    @Test
    @DisplayName("[SIM-01.06][AT-SIM-04.4][TC-SIM-012] 재실 0명 → 소음 30±3dB, 30명 수업 → 55~70dB")
    void noise() {
        PhysicsHarness quiet = PhysicsHarness.classroom();
        assertThat(quiet.run(60, s -> s.noise).getLast()).isBetween(PhysicsTolerances.QUIET_NOISE - PhysicsTolerances.QUIET_NOISE_BAND,
                PhysicsTolerances.QUIET_NOISE + PhysicsTolerances.QUIET_NOISE_BAND);
        PhysicsHarness lecture = PhysicsHarness.classroom().state(s -> {
            s.occupancy = 30;
            s.activityLevel = 2;
        });
        assertThat(lecture.run(60, s -> s.noise).getLast()).isBetween(55.0, 70.0);
    }

    @Test
    @DisplayName("[SIM-01.06][AT-SIM-04.4][TC-SIM-012] 조명 ON → ≥ 400lx, 조명 OFF 야간 → < 10lx, 일출·일몰은 위도 37.5°·날짜로 계산")
    void illumination() {
        PhysicsHarness lit = PhysicsHarness.classroom().effects(e -> e.lightLux = 500);
        assertThat(lit.run(10, s -> s.illumination).getLast()).isGreaterThanOrEqualTo(400);
        PhysicsHarness dark = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50));
        assertThat(dark.run(10, s -> s.illumination).getLast()).isLessThan(10);

        SolarModel sun = SolarModel.seoul();
        // 2026-08-10 서울 일출 약 05:50, 일몰 약 19:35(KST)
        assertThat(sun.elevation(Instant.parse("2026-08-09T20:30:00Z"))).isNegative();   // 05:30 KST
        assertThat(sun.elevation(Instant.parse("2026-08-09T21:15:00Z"))).isPositive();   // 06:15 KST
        assertThat(sun.elevation(Instant.parse("2026-08-10T10:15:00Z"))).isPositive();   // 19:15 KST
        assertThat(sun.elevation(Instant.parse("2026-08-10T11:00:00Z"))).isNegative();   // 20:00 KST
        PhysicsHarness day = PhysicsHarness.classroom().at(Instant.parse("2026-08-10T03:00:00Z"));
        assertThat(day.run(10, s -> s.illumination).getLast()).isGreaterThan(100);
    }
}
