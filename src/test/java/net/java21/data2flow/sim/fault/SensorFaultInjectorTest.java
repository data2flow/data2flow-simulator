package net.java21.data2flow.sim.fault;

import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensorFaultInjectorTest {

    static final Instant FROM = Worlds.START.plusSeconds(3600);
    static final Instant TO = FROM.plusSeconds(1800);

    static SimulationWorld run(FaultKind kind, Map<String, Object> params) {
        Worlds w = Worlds.classroom().hours(3).outdoor(OutdoorSpec.constant(33, 60))
                .device(Worlds.dev(1, "온습도", "th-sensor").interval(60).prop("temperatureAccuracy", 0.0)
                        .prop("humidityAccuracy", 0.0).build())
                .fault(new FaultSpec(11, kind, FaultSpec.DEVICE, "1", params, FROM, TO));
        SimulationWorld world = w.world();
        Worlds.runToEnd(world);
        return world;
    }

    static List<SimulationWorld.Reading> temps(SimulationWorld w) {
        return w.readings().stream().filter(r -> r.metric().equals("temperature")).toList();
    }

    static boolean inside(SimulationWorld.Reading r) {
        return !r.simTime().isBefore(FROM) && r.simTime().isBefore(TO);
    }

    @Test
    @DisplayName("[SIM-05.01][AT-SIM-11.1][TC-SIM-059] STUCK 30분 → 그 구간 보고 값 모두 같고, 전후는 정상")
    void stuck() {
        List<SimulationWorld.Reading> t = temps(run(FaultKind.STUCK, Map.of()));
        List<Double> in = t.stream().filter(SensorFaultInjectorTest::inside).map(SimulationWorld.Reading::value).distinct().toList();
        assertThat(in).hasSize(1);
        List<Double> after = t.stream().filter(r -> !r.simTime().isBefore(TO)).map(SimulationWorld.Reading::value).distinct().toList();
        assertThat(after.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("[SIM-05.01][AT-SIM-11.2][TC-SIM-059] SPIKE +20 ×3 → 정확히 3개 지점만 +20")
    void spike() {
        List<SimulationWorld.Reading> normal = temps(run(FaultKind.STUCK, Map.of("value", 0.0, "metric", "humidity")));
        List<SimulationWorld.Reading> spiked = temps(run(FaultKind.SPIKE, Map.of("magnitude", 20, "count", 3)));
        assertThat(spiked).hasSameSizeAs(normal);
        int diff = 0;
        for (int i = 0; i < normal.size(); i++) {
            double d = spiked.get(i).value() - normal.get(i).value();
            if (Math.abs(d) > 1e-9) {
                assertThat(d).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-9));
                diff++;
            }
        }
        assertThat(diff).isEqualTo(3);
    }

    @Test
    @DisplayName("[SIM-05.01][AT-SIM-11.1][TC-SIM-059] DRIFT +0.5/시간 → 선형 증가, DROPOUT → 보고 0, INTERMITTENT 50% → 0.5±0.1")
    void driftDropoutIntermittent() {
        List<SimulationWorld.Reading> base = temps(run(FaultKind.STUCK, Map.of("value", 0.0, "metric", "humidity")));
        List<SimulationWorld.Reading> drift = temps(run(FaultKind.DRIFT, Map.of("perHour", 6.0)));
        SimulationWorld.Reading lastIn = null;
        SimulationWorld.Reading baseIn = null;
        for (int i = 0; i < base.size(); i++) {
            if (inside(base.get(i))) {
                lastIn = drift.get(i);
                baseIn = base.get(i);
            }
        }
        assertThat(lastIn.value() - baseIn.value()).isBetween(2.5, 3.1);   // 6/h × 약 29분

        assertThat(temps(run(FaultKind.DROPOUT, Map.of())).stream().filter(SensorFaultInjectorTest::inside)).isEmpty();
        long inter = temps(run(FaultKind.INTERMITTENT, Map.of("onSec", 300, "offSec", 300))).stream()
                .filter(SensorFaultInjectorTest::inside).count();
        assertThat(inter / 30.0).isBetween(0.4, 0.6);
    }

    @Test
    @DisplayName("[SIM-05.01][AT-SIM-11.1][TC-SIM-059] BATTERY_DRAIN → 배터리 급감, OUT_OF_RANGE → 유효 범위 밖 값")
    void batteryAndRange() {
        SimulationWorld drained = run(FaultKind.BATTERY_DRAIN, Map.of("pctPerHour", 40));
        assertThat(drained.sensorRuntime(1).battery).isLessThan(85);
        List<SimulationWorld.Reading> oor = temps(run(FaultKind.OUT_OF_RANGE, Map.of()));
        assertThat(oor.stream().filter(SensorFaultInjectorTest::inside)).allMatch(r -> r.value() > 85);
        assertThat(temps(run(FaultKind.OUT_OF_RANGE, Map.of("value", -99.0))).stream().filter(SensorFaultInjectorTest::inside))
                .allMatch(r -> r.value() == -99.0);
    }

    @Test
    @DisplayName("[SIM-05.04][AT-SIM-11.1][TC-SIM-067] 주입한 장애마다 정답 라벨(시작·끝) 정확히 1건씩")
    void labels() {
        Worlds w = Worlds.classroom().hours(2)
                .device(Worlds.dev(1, "온습도", "th-sensor").interval(60).build())
                .fault(new FaultSpec(11, FaultKind.STUCK, FaultSpec.DEVICE, "1", Map.of(), FROM, TO));
        List<Emission> out = Worlds.runToEnd(w.world());
        List<Emission.FaultLabel> labels = out.stream().filter(e -> e instanceof Emission.FaultLabel).map(e -> (Emission.FaultLabel) e)
                .toList();
        assertThat(labels).hasSize(2);
        assertThat(labels.get(0).started()).isTrue();
        assertThat(labels.get(0).at()).isEqualTo(FROM);
        assertThat(labels.get(1).started()).isFalse();
        assertThat(labels.get(1).at()).isEqualTo(TO);
    }
}
