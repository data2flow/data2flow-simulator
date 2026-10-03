package net.java21.data2flow.sim.physics;

import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.physics.domain.SpacePreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpacePhysicsSettingsTest {

    @Test
    @DisplayName("[SIM-01.02][AT-SIM-04.2][TC-SIM-003] 프리셋 강의실(66㎡·3m·198㎥)·사무실·회의실 기본값, 면적 0·단열 계수 범위 밖 → 위반 목록")
    void presets() {
        SpacePhysics classroom = SpacePhysics.preset(SpacePreset.CLASSROOM);
        assertThat(classroom.areaM2()).isEqualTo(66);
        assertThat(classroom.heightM()).isEqualTo(3);
        assertThat(classroom.volumeM3()).isEqualTo(198);
        for (SpacePreset p : SpacePreset.values()) {
            assertThat(SpacePhysics.preset(p).violations()).as(p.name()).isEmpty();
        }
        SpacePhysics bad = new SpacePhysics(SpacePreset.CUSTOM, 0, 3, 9, 10, null, null, 2, null, true, 420, null, 30,
                Map.of("temperature", -1.0));
        List<String> fields = bad.violations().stream().map(v -> v[0]).toList();
        assertThat(fields).contains("physics.areaM2", "physics.uValue", "physics.solarGainFactor", "physics.initialState",
                "physics.perPerson", "physics.noiseStd.temperature");
        SpacePhysics noPreset = new SpacePhysics(null, 50, 3, 1, 10, 2.0, "N", 0.1, classroom.initialState(), false, 420,
                classroom.perPerson(), 30, Map.of());
        assertThat(noPreset.violations().stream().map(v -> v[0]).toList()).containsExactly("physics.preset");
        assertThat(noPreset.window()).isEqualTo(2.0);
    }
}
