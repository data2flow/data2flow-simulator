package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class SensorCharacteristicsTest {

    @Test
    @DisplayName("[SIM-09.05][AT-SIM-03.3][TC-SIM-101] 오차 0·분해능 0.1 → 출력 = 물리 값 소수 1자리 반올림, 지연 60초 → 60초 전 물리 값")
    void zeroErrorAndDelay() {
        for (int delay : new int[]{0, 60}) {
            Worlds w = Worlds.classroom().hours(2).outdoor(OutdoorSpec.constant(33, 60))
                    .device(Worlds.dev(1, "온습도", "th-sensor").interval(10).prop("temperatureAccuracy", 0.0)
                            .prop("responseDelaySec", delay).build());
            SimulationWorld world = w.world();
            List<double[]> physical = new ArrayList<>();   // (틱 끝 ms, 온도)
            while (!world.finished()) {
                world.step(Instant.EPOCH);
                physical.add(new double[]{world.simNow().toEpochMilli(), world.space(Worlds.SPACE).temperature});
            }
            List<SimulationWorld.Reading> readings = world.readings().stream().filter(r -> r.metric().equals("temperature")).toList();
            assertThat(readings.size()).isGreaterThan(600);
            int matched = 0;
            for (SimulationWorld.Reading r : readings) {
                long target = r.simTime().toEpochMilli() - delay * 1000L;
                double expected = Double.NaN;
                for (double[] p : physical) {
                    if (p[0] <= target) {
                        expected = p[1];
                    }
                }
                if (!Double.isNaN(expected)) {
                    assertThat(r.value()).isCloseTo(Math.round(expected * 10) / 10.0, offset(1e-9));
                    matched++;
                }
            }
            assertThat(matched).isGreaterThan(500);
        }
    }

    @Test
    @DisplayName("[SIM-09.05][AT-SIM-03.4][TC-SIM-101] 드리프트 연 +20ppm·잡음 0·365일 → +20ppm ±1, 같은 시드 두 번 같다")
    void drift() {
        Worlds w = Worlds.classroom().tickSec(3600).hours(365 * 24).outdoor(OutdoorSpec.constant(25, 50))
                .device(Worlds.dev(1, "CO2", "co2-sensor").interval(86_400).prop("co2Accuracy", 0.0).prop("responseDelaySec", 0).build());
        SimulationWorld a = w.world();
        Worlds.runToEnd(a);
        List<SimulationWorld.Reading> co2 = a.readings().stream().filter(r -> r.metric().equals("co2")).toList();
        // 첫 이틀은 초기 450ppm이 외기 420ppm으로 내려가는 구간이라 그 뒤부터 비교한다
        SimulationWorld.Reading first = co2.get(2);
        SimulationWorld.Reading last = co2.getLast();
        double years = (last.simTime().toEpochMilli() - first.simTime().toEpochMilli()) / (365.0 * 86_400_000);
        assertThat(last.value() - first.value()).isCloseTo(20 * years, offset(1.0));
        assertThat(years).isGreaterThan(0.98);
        SimulationWorld b = w.world();
        Worlds.runToEnd(b);
        assertThat(b.digest()).isEqualTo(a.digest());
    }
}
