package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldSpace;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.physics.domain.SpacePreset;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class SensorNoiseTest {

    /** 창 없고(일사 0) 외기 = 실내 = 26℃인 조용한 공간: 물리 값이 변하지 않는다 */
    static WorldSpace steadyRoom() {
        SpacePhysics p = SpacePhysics.preset(SpacePreset.CLASSROOM);
        SpacePhysics steady = new SpacePhysics(p.preset(), p.areaM2(), p.heightM(), p.uValue(), p.envelopeM2(), 0.0, "S", 0,
                p.initialState(), p.outdoorLinked(), p.outdoorCo2Ppm(), p.perPerson(), p.backgroundNoiseDb(), Map.of());
        return new WorldSpace(Worlds.SPACE, "정상 상태 공간", steady, false);
    }

    @Test
    @DisplayName("[SIM-01.07][AT-SIM-04.5][TC-SIM-013] 같은 공간 센서 2대 σ=0.2 → 같은 값 < 30%, 차이 평균 0±0.05, 표준편차 0.2±0.03, 상관 < 0.1, 소수 1자리")
    void independentNoise() {
        Worlds w = new Worlds().space(steadyRoom()).seed(42).outdoor(OutdoorSpec.constant(26, 55))
                .start(Instant.parse("2026-08-10T15:00:00Z")).hours(1000 / 60.0 + 0.2)
                .device(Worlds.dev(1, "온습도-1", "th-sensor").interval(60).prop("temperatureAccuracy", 0.4)
                        .prop("responseDelaySec", 0).build())
                .device(Worlds.dev(2, "온습도-2", "th-sensor").interval(60).prop("temperatureAccuracy", 0.4)
                        .prop("responseDelaySec", 0).build());
        SimulationWorld world = w.world();
        Worlds.runToEnd(world);
        List<Double> a = values(world, "온습도-1");
        List<Double> b = values(world, "온습도-2");
        int n = Math.min(a.size(), b.size());
        assertThat(n).isGreaterThanOrEqualTo(1000);
        int same = 0;
        double sumDiff = 0;
        for (int i = 0; i < n; i++) {
            if (a.get(i).equals(b.get(i))) {
                same++;
            }
            sumDiff += a.get(i) - b.get(i);
        }
        assertThat(same / (double) n).isLessThan(0.3);
        assertThat(sumDiff / n).isCloseTo(0, offset(0.05));
        double meanA = mean(a.subList(0, n));
        double meanB = mean(b.subList(0, n));
        double sa = std(a.subList(0, n), meanA);
        double sb = std(b.subList(0, n), meanB);
        assertThat(sa).isCloseTo(0.2, offset(0.03));
        assertThat(sb).isCloseTo(0.2, offset(0.03));
        double cov = 0;
        for (int i = 0; i < n; i++) {
            cov += (a.get(i) - meanA) * (b.get(i) - meanB);
        }
        assertThat(Math.abs(cov / n / (sa * sb))).isLessThan(0.1);
        assertThat(a).allMatch(v -> BigDecimal.valueOf(v).stripTrailingZeros().scale() <= 1);
    }

    static List<Double> values(SimulationWorld w, String device) {
        return w.readings().stream().filter(r -> r.device().equals(device) && r.metric().equals("temperature"))
                .map(SimulationWorld.Reading::value).toList();
    }

    static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    static double std(List<Double> v, double mean) {
        return Math.sqrt(v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (v.size() - 1));
    }
}
