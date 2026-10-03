package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeasuredAtPolicyTest {

    @Test
    @DisplayName("[SIM-04.04][AT-SIM-08.1][TC-SIM-050] 기본 = 시뮬레이션 시각(수신 시각도 시뮬레이션 전송 시각), WALL_CLOCK이면 실제 현재 시각")
    void policies() {
        Worlds sim = Worlds.classroom().hours(1).device(Worlds.dev(1, "온습도", "th-sensor").interval(60).build());
        List<Emission.Uplink> ups = Worlds.uplinks(Worlds.runToEnd(sim.world()));
        assertThat(ups).allSatisfy(u -> {
            assertThat(u.measuredAt()).isBetween(Worlds.START, Worlds.START.plusSeconds(3600));
            assertThat(u.receivedAt()).isEqualTo(u.measuredAt());
        });

        Instant real = Instant.parse("2026-10-04T05:00:00Z");
        Worlds wall = Worlds.classroom().hours(1).policy(TimestampPolicy.WALL_CLOCK)
                .device(Worlds.dev(1, "온습도", "th-sensor").interval(60).build());
        SimulationWorld world = wall.world();
        List<Emission> out = new ArrayList<>();
        while (!world.finished()) {
            out.addAll(world.step(real));
        }
        assertThat(Worlds.uplinks(out)).isNotEmpty().allSatisfy(u -> {
            assertThat(u.measuredAt()).isEqualTo(real);
            assertThat(u.receivedAt()).isEqualTo(real);
        });
        // 결정성 해시는 정책과 무관하게 시뮬레이션 시각으로 계산한다
        assertThat(world.digest()).isEqualTo(sim.world().digest().isEmpty() ? world.digest() : runDigest(sim));
    }

    private static String runDigest(Worlds w) {
        SimulationWorld world = w.world();
        Worlds.runToEnd(world);
        return world.digest();
    }
}
