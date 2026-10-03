package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;
import net.java21.data2flow.sim.sensor.domain.GeneratorState;
import net.java21.data2flow.sim.sensor.domain.Generators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RandomWalkAndScheduleGeneratorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant T = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.1][TC-SIM-019] 고정값은 그대로, 랜덤 워크는 10만 걸음 동안 18~28을 벗어나지 않는다")
    void fixedAndRandomWalk() {
        assertThat(Generators.sample(GeneratorSpec.fixed(21.5), new GeneratorState(), T, SEOUL, 1, "d", "m", 0)).isEqualTo(21.5);
        GeneratorSpec walk = GeneratorSpec.randomWalk(23, 18, 28, 0.8);
        GeneratorState s = new GeneratorState();
        double min = 1e9;
        double max = -1e9;
        for (int i = 0; i < 100_000; i++) {
            double v = Generators.sample(walk, s, T, SEOUL, 3, "d", "m", i);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertThat(min).isGreaterThanOrEqualTo(18);
        assertThat(max).isLessThanOrEqualTo(28);
        assertThat(max - min).isGreaterThan(5);
    }

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.1][TC-SIM-019] 시간표: 09~18시 22, 그 외 18")
    void schedule() {
        GeneratorSpec spec = new GeneratorSpec(GeneratorSpec.SCHEDULE, Map.of("segments",
                List.of(Map.of("from", "09:00", "to", "18:00", "value", 22)), "defaultValue", 18));
        assertThat(Generators.validate(spec)).isNull();
        assertThat(Generators.sample(spec, new GeneratorState(), Instant.parse("2026-08-10T00:00:00Z"), SEOUL, 1, "d", "m", 0))
                .isEqualTo(22);   // 09:00 KST
        assertThat(Generators.sample(spec, new GeneratorState(), Instant.parse("2026-08-10T08:59:00Z"), SEOUL, 1, "d", "m", 0))
                .isEqualTo(22);   // 17:59
        assertThat(Generators.sample(spec, new GeneratorState(), Instant.parse("2026-08-10T09:00:00Z"), SEOUL, 1, "d", "m", 0))
                .isEqualTo(18);   // 18:00
        assertThat(Generators.sample(spec, new GeneratorState(), Instant.parse("2026-08-09T22:00:00Z"), SEOUL, 1, "d", "m", 0))
                .isEqualTo(18);   // 07:00
    }

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.1][TC-SIM-019] 생성기 설정 검증")
    void validation() {
        assertThat(Generators.validate(new GeneratorSpec("FIXED", Map.of()))).isEqualTo("value");
        assertThat(Generators.validate(GeneratorSpec.randomWalk(20, 30, 10, 1))).isNotNull();
        assertThat(Generators.validate(GeneratorSpec.diurnal(20, 3, 30))).isEqualTo("peakHour");
        assertThat(Generators.validate(GeneratorSpec.event(-1, 10, 1, 0))).isNotNull();
        assertThat(Generators.validate(new GeneratorSpec("SCHEDULE", Map.of("segments", List.of(Map.of("from", "x", "to", "y",
                "value", 1)))))).isNotNull();
        assertThat(Generators.validate(new GeneratorSpec("NOPE", Map.of()))).isEqualTo("kind");
        assertThat(Generators.validate(null)).isEqualTo("kind");
    }
}
