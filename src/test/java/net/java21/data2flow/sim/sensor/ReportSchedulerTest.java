package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.sensor.domain.ReportScheduler;
import net.java21.data2flow.sim.support.PhysicsTolerances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ReportSchedulerTest {

    @Test
    @DisplayName("[SIM-02.05][AT-SIM-05.3][TC-SIM-024] 주기 60초·지터 10% → 100회 간격 모두 54~66초, 평균 60±1초, 지터 0이면 정확히 60초")
    void jitter() {
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            long ms = ReportScheduler.nextIntervalMs(60, 10, 42, "온습도-1", i);
            assertThat(ms).isBetween(PhysicsTolerances.JITTER_MIN_MS, PhysicsTolerances.JITTER_MAX_MS);
            sum += ms;
        }
        assertThat(sum / 100_000.0).isCloseTo(60, offset(1.0));
        for (int i = 0; i < 10; i++) {
            assertThat(ReportScheduler.nextIntervalMs(60, 0, 42, "온습도-1", i)).isEqualTo(60_000);
        }
        assertThat(ReportScheduler.firstOffsetMs(60, 42, "온습도-1")).isBetween(0L, 60_000L);
    }
}
