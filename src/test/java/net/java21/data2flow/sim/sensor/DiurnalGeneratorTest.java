package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;
import net.java21.data2flow.sim.sensor.domain.GeneratorState;
import net.java21.data2flow.sim.sensor.domain.Generators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DiurnalGeneratorTest {

    @Test
    @DisplayName("[SIM-02.03][AT-SIM-05.1][TC-SIM-017] 평균 24·진폭 3·피크 15시, 하루 1,440점 → 최댓값 15:00±30분 26.8~27.0, 최솟값 03:00±30분 21.0~21.2")
    void diurnal() {
        GeneratorSpec spec = GeneratorSpec.diurnal(24, 3, 15);
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Instant midnight = Instant.parse("2026-08-09T15:00:00Z");
        double max = -1e9;
        double min = 1e9;
        int maxAt = -1;
        int minAt = -1;
        for (int m = 0; m < 1440; m++) {
            double v = Generators.sample(spec, new GeneratorState(), midnight.plusSeconds(m * 60L), zone, 1, "d", "t", m);
            if (v > max) {
                max = v;
                maxAt = m;
            }
            if (v < min) {
                min = v;
                minAt = m;
            }
        }
        assertThat(maxAt).isBetween(15 * 60 - 30, 15 * 60 + 30);
        assertThat(max).isBetween(26.8, 27.0);
        assertThat(minAt).isBetween(3 * 60 - 30, 3 * 60 + 30);
        assertThat(min).isBetween(21.0, 21.2);
    }
}
