package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.device.domain.ResponseSettings;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedDeterminismTest {

    static Worlds scenario(long seed, boolean reversed) {
        Worlds w = Worlds.classroom().seed(seed).hours(6).outdoor(OutdoorSpec.diurnal(35, 27, 15, 55))
                .event(new ScenarioEvent("in", "OCCUPANCY", Worlds.START.plusSeconds(600), Worlds.START.plusSeconds(4 * 3600),
                        Map.of("spaceId", Worlds.SPACE), Map.of("count", 25)))
                .event(new ScenarioEvent("ac", "ACTUATOR", Worlds.START.plusSeconds(3600), null, Map.of("deviceId", 20),
                        Map.of("capability", "Thermostat", "command", "set", "args", Map.of("mode", "cool", "targetTemperature", 24))))
                .fault(new FaultSpec(1, FaultKind.STUCK, FaultSpec.DEVICE, "1", Map.of(), Worlds.START.plusSeconds(7200),
                        Worlds.START.plusSeconds(9000)))
                .fault(new FaultSpec(2, FaultKind.DROPOUT, FaultSpec.DEVICE, "2", Map.of("ratio", 0.3), Worlds.START.plusSeconds(3600),
                        Worlds.START.plusSeconds(9000)))
                .fault(new FaultSpec(3, FaultKind.SPIKE, FaultSpec.DEVICE, "3", Map.of("magnitude", 300, "count", 2),
                        Worlds.START.plusSeconds(1000), Worlds.START.plusSeconds(9000)));
        List<WorldDevice> devices = List.of(
                Worlds.dev(1, "온습도-1", "th-sensor").interval(60).jitter(10).build(),
                Worlds.dev(2, "온습도-2", "th-sensor").interval(60).jitter(10).build(),
                Worlds.dev(3, "CO2", "co2-sensor").interval(60).jitter(5).build(),
                Worlds.dev(4, "재실", "pir-sensor").interval(60).build(),
                Worlds.dev(5, "AM107", "am107").interval(120).build(),
                Worlds.dev(20, "에어컨", "aircon").response(new ResponseSettings(60, 200, 20.0)).build());
        List<WorldDevice> order = reversed ? devices.reversed() : devices;
        order.forEach(w::device);
        return w;
    }

    static SimulationWorld run(Worlds w) {
        SimulationWorld world = w.world();
        Worlds.runToEnd(world);
        return world;
    }

    @Test
    @DisplayName("[SIM-08.03][AT-SIM-08.3][TC-SIM-088] 같은 시드 2회 → 잡음·지터·이벤트·실패 확률까지 같은 SHA-256, 다른 시드 → 다름")
    void sameSeedSameHash() {
        SimulationWorld a = run(scenario(42, false));
        SimulationWorld b = run(scenario(42, false));
        SimulationWorld c = run(scenario(43, false));
        assertThat(a.readingCount()).isGreaterThan(1000);
        assertThat(a.digest()).isEqualTo(b.digest()).hasSize(64);
        assertThat(SimulationWorld.sortedHash(a.readings())).isEqualTo(SimulationWorld.sortedHash(b.readings()));
        assertThat(c.digest()).isNotEqualTo(a.digest());
        assertThat(SimulationWorld.sortedHash(c.readings())).isNotEqualTo(SimulationWorld.sortedHash(a.readings()));
    }

    @Test
    @DisplayName("[SIM-08.03][AT-SIM-08.3][TC-SIM-088] 기기 추가 순서가 바뀌어도 같은 값(이름으로 난수 분기), 기기를 더해도 다른 기기 값 불변")
    void orderIndependent() {
        SimulationWorld a = run(scenario(42, false));
        SimulationWorld b = run(scenario(42, true));
        assertThat(b.digest()).isEqualTo(a.digest());

        Worlds more = scenario(42, false).device(Worlds.dev(99, "추가 센서", "lux-sensor").interval(60).build());
        SimulationWorld c = run(more);
        List<SimulationWorld.Reading> base = a.readings().stream().filter(r -> r.device().equals("온습도-1")).toList();
        List<SimulationWorld.Reading> withExtra = c.readings().stream().filter(r -> r.device().equals("온습도-1")).toList();
        assertThat(withExtra).isEqualTo(base);
    }

    @Test
    @DisplayName("[SIM-08.03][AT-SIM-08.2][TC-SIM-045] 체크포인트(JSON)에서 이어 실행해도 끝까지 같은 해시")
    void resumeFromCheckpoint() {
        SimulationWorld straight = run(scenario(42, false));
        Worlds w = scenario(42, false);
        WorldConfig cfg = w.config();
        SimulationWorld first = SimulationWorld.start(cfg);
        for (int i = 0; i < 1000; i++) {
            first.step(Instant.EPOCH);
        }
        String checkpoint = first.snapshotJson();
        SimulationWorld resumed = SimulationWorld.resume(cfg, SimulationWorld.parseSnapshot(checkpoint));
        while (!resumed.finished()) {
            resumed.step(Instant.EPOCH);
        }
        assertThat(resumed.digest()).isEqualTo(straight.digest());
        assertThat(resumed.readingCount()).isEqualTo(straight.readingCount());
    }

    @Test
    @DisplayName("[SIM-08.03][AT-SIM-08.3][TC-SIM-089] 틱 기반이라 실행 간격(가속)과 무관: 한 번에 다 돌리든 나눠 돌리든 같다")
    void accelerationIndependent() {
        SimulationWorld a = run(scenario(7, false));
        SimulationWorld b = scenario(7, false).world();
        int batch = 0;
        while (!b.finished()) {
            for (int i = 0; i < (batch % 2 == 0 ? 6 : 1) && !b.finished(); i++) {
                b.step(Instant.EPOCH);
            }
            batch++;
        }
        assertThat(b.digest()).isEqualTo(a.digest());
    }
}
