package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.PhysicsHarness;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThermalModelConvergenceTest {

    @Test
    @DisplayName("[SIM-01.03][AT-SIM-04.1][TC-SIM-007] 냉방 24℃ → 24±1℃ 수렴, 오버슈트 ≤ 1℃, 10분 이동평균 변화 < 0.1℃")
    void coolingConverges() {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(33, 60))
                .state(s -> {
                    s.temperature = 30;
                    s.occupancy = 20;
                })
                .effects(PhysicsHarness.cooling(3.5, 24));
        List<Double> temps = h.run(3 * 3600, s -> s.temperature);
        double min = temps.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        assertThat(min).isGreaterThanOrEqualTo(24 - PhysicsTolerances.SETPOINT_BAND);
        double last = temps.getLast();
        assertThat(last).isBetween(24 - PhysicsTolerances.SETPOINT_BAND, 24 + PhysicsTolerances.SETPOINT_BAND);
        double avgA = temps.subList(temps.size() - 1200, temps.size() - 600).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double avgB = temps.subList(temps.size() - 600, temps.size()).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertThat(Math.abs(avgB - avgA)).isLessThan(PhysicsTolerances.STABLE_CHANGE_PER_10MIN);
    }

    @Test
    @DisplayName("[SIM-01.03][AT-SIM-04.1][TC-SIM-007] 난방 18℃ → 22℃ 설정으로 수렴")
    void heatingConverges() {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(5, 40))
                .state(s -> s.temperature = 18)
                .effects(PhysicsHarness.heating(4.0, 22));
        double last = h.run(3 * 3600, s -> s.temperature).getLast();
        assertThat(last).isBetween(22 - PhysicsTolerances.SETPOINT_BAND, 22 + PhysicsTolerances.SETPOINT_BAND);
    }

    @Test
    @DisplayName("[SIM-01.03][AT-SIM-04.1][TC-SIM-007] 외기 = 실내, 재실·일사 없음 → 변화 0(에너지 보존)")
    void equilibriumStaysPut() {
        PhysicsHarness h = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> s.temperature = 25);
        double last = h.run(3600, s -> s.temperature).getLast();
        assertThat(last).isCloseTo(25, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("[SIM-01.03][AT-SIM-04.1][TC-SIM-007] 일사(정오, 조도 50,000lx 이상)가 있으면 밤보다 더 오른다")
    void solarGainAdds() {
        Instant noon = Instant.parse("2026-08-10T03:00:00Z");
        PhysicsHarness day = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50)).at(noon)
                .state(s -> s.temperature = 25);
        double dayT = day.run(3600, s -> s.temperature).getLast();
        PhysicsHarness night = PhysicsHarness.classroom().outdoor(OutdoorSpec.constant(25, 50))
                .state(s -> s.temperature = 25);
        double nightT = night.run(3600, s -> s.temperature).getLast();
        assertThat(dayT).isGreaterThan(nightT + 0.1);
    }
}
