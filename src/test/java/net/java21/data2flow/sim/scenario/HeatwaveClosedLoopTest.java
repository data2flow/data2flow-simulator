package net.java21.data2flow.sim.scenario;

import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.scenario.domain.DemoPresets;
import net.java21.data2flow.sim.scenario.domain.Scenario;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 시연 "폭염 오후"의 폐루프를 서비스 없이 확인한다(SIM-03.03, SIM-04.05). 플로우 "27℃ 이상 5분 지속 → 에어컨 냉방 24℃"와
 * ACT virtual 드라이버 자리는 측정값을 보고 명령을 넣는 대역이 맡는다. 서비스 전체 E2E(TC-SIM-034)는 compose 스택 몫이다.
 */
class HeatwaveClosedLoopTest {

    static Worlds heatwave(long seed) {
        DemoPresets.PresetDef p = DemoPresets.find(DemoPresets.HEATWAVE_AFTERNOON).orElseThrow();
        Scenario s = DemoPresets.materialize(p, "폭염 오후", Worlds.SPACE,
                Map.of("th-sensor", List.of(1L, 2L), "co2-sensor", List.of(3L), "pir-sensor", List.of(4L), "aircon", List.of(10L)),
                "gw");
        Worlds w = Worlds.classroom().seed(seed).start(s.simStartAt()).hours(s.durationSec() / 3600.0).outdoor(s.outdoor());
        s.events().forEach(w::event);
        s.expectations().forEach(w::expectation);
        w.device(Worlds.dev(1, "온습도-1", "th-sensor").build())
                .device(Worlds.dev(2, "온습도-2", "th-sensor").build())
                .device(Worlds.dev(3, "CO2", "co2-sensor").build())
                .device(Worlds.dev(4, "재실", "pir-sensor").build())
                .device(Worlds.dev(10, "에어컨", "aircon").build());
        return w;
    }

    @Test
    @DisplayName("[SIM-03.03][AT-SIM-06.1][TC-SIM-034] 폭염 오후: 27℃ 이상 5분 → 가상 에어컨 냉방 24℃ → 온도 하강 → 24±1℃ 안정, 사람 개입 없음")
    void closedLoop() {
        SimulationWorld world = heatwave(42).world();
        ArrayDeque<double[]> window = new ArrayDeque<>();   // (측정 ms, 온도)
        Instant commandedAt = null;
        List<double[]> physical = new ArrayList<>();
        while (!world.finished()) {
            List<Emission> out = world.step(Instant.EPOCH);
            physical.add(new double[]{world.simNow().toEpochMilli(), world.space(Worlds.SPACE).temperature});
            for (SimulationWorld.Reading r : world.readings().subList(Math.max(0, world.readings().size() - 20), world.readings().size())) {
                if (r.metric().equals("temperature") && r.device().startsWith("온습도")
                        && (window.isEmpty() || r.simTime().toEpochMilli() > window.peekLast()[0])) {
                    window.add(new double[]{r.simTime().toEpochMilli(), r.value()});
                }
            }
            while (!window.isEmpty() && window.peekFirst()[0] < world.simNow().toEpochMilli() - 5 * 60_000) {
                window.pollFirst();
            }
            boolean hot = window.size() >= 4 && window.stream().allMatch(v -> v[1] >= 27);
            if (hot && commandedAt == null) {
                // 플로우 → 제어 창구 → virtual 드라이버(API-SIM-30) 자리
                WorldState.InboundCommand c = new WorldState.InboundCommand();
                c.commandId = "flow-hot-then-cool";
                c.deviceId = 10;
                c.capability = "Thermostat";
                c.command = "set";
                c.args.put("mode", "cool");
                c.args.put("targetTemperature", 24);
                c.source = "FLOW";
                world.submit(c);
                commandedAt = world.simNow();
            }
            assertThat(out).noneMatch(e -> e instanceof Emission.CommandAck a && !"ACKED".equals(a.result()));
        }
        assertThat(commandedAt).as("27℃ 이상 5분이 되어 에어컨을 켬").isNotNull();
        assertThat(commandedAt).isBefore(world.config().simStart().plusSeconds(3 * 3600));
        double peak = physical.stream().filter(p -> p[0] > commandedAtMs(commandedAt)).mapToDouble(p -> p[1]).max().orElseThrow();
        double last = physical.getLast()[1];
        assertThat(last).isLessThan(peak).isBetween(24 - PhysicsTolerances.SETPOINT_BAND, 24 + PhysicsTolerances.SETPOINT_BAND);
        List<double[]> tail = physical.subList(physical.size() - 120, physical.size());
        double avgA = tail.subList(0, 60).stream().mapToDouble(p -> p[1]).average().orElseThrow();
        double avgB = tail.subList(60, 120).stream().mapToDouble(p -> p[1]).average().orElseThrow();
        assertThat(Math.abs(avgB - avgA)).isLessThan(PhysicsTolerances.STABLE_CHANGE_PER_10MIN);
        world.finalizeExpectations(false);
        assertThat(world.expectations().get("ac-on").state).isEqualTo("PASSED");
        assertThat(world.expectations().get("ac-cycles").state).isEqualTo("PASSED");
        assertThat(world.stats().controlCount).isEqualTo(1);
        assertThat(world.stats().energyKwh).isPositive();
    }

    private static long commandedAtMs(Instant at) {
        return at.toEpochMilli();
    }

    @Test
    @DisplayName("[SIM-04.05][AT-SIM-09.5][TC-SIM-051] 프리셋 5종: 공간·기기·시나리오·플로우 묶음, 자리표시자를 실제 ID로 푼다")
    void presets() {
        assertThat(DemoPresets.all()).extracting(DemoPresets.PresetDef::key).containsExactly("classroom-crowded",
                "heatwave-afternoon", "sensor-failure", "gateway-outage", "night-unmanned");
        DemoPresets.PresetDef gw = DemoPresets.find(DemoPresets.GATEWAY_OUTAGE).orElseThrow();
        Scenario s = DemoPresets.materialize(gw, "게이트웨이 장애", 5L, Map.of("th-sensor", List.of(1L, 2L, 3L, 4L, 5L)), "gw-eui");
        assertThat(s.events().get(0).target().get("targetIds")).isEqualTo(List.of("gw-eui"));
        assertThat(gw.deviceCount()).isEqualTo(5);
        DemoPresets.PresetDef heat = DemoPresets.find(DemoPresets.HEATWAVE_AFTERNOON).orElseThrow();
        Map<String, Map<String, Object>> flows = DemoPresets.flowBindings(heat, 5L,
                Map.of("th-sensor", List.of(1L, 2L), "co2-sensor", List.of(3L), "pir-sensor", List.of(4L), "aircon", List.of(9L)), "gw");
        assertThat(flows.get("hot-then-cool").get("airconId")).isEqualTo(9L);
        assertThat(flows.get("hot-then-cool").get("temperatureSensorIds")).isEqualTo(List.of("1", "2"));
        assertThat(heat.durationSec()).isEqualTo(86_400);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DemoPresets.materialize(heat, "x", 5L, Map.of(), "gw"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
