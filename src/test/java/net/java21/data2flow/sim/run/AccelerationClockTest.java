package net.java21.data2flow.sim.run;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.run.domain.AccelerationClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccelerationClockTest {

    static final Instant T = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    @DisplayName("[SIM-04.03][AT-SIM-08.1][TC-SIM-048] x60이면 실제 1초 = 시뮬레이션 60초(10초 틱 6개), x0·x61 거부, 가속을 바꿔도 시각 연속")
    void acceleration() {
        AccelerationClock c = new AccelerationClock(T, 0, 60, 10);
        assertThat(c.targetTick(T.plusSeconds(1))).isEqualTo(6);
        assertThat(c.targetTick(T.plusSeconds(24 * 60))).isEqualTo(8640);   // 하루 = 24분
        assertThat(AccelerationClock.realSeconds(86_400, 60)).isEqualTo(1440.0);
        assertThat(new AccelerationClock(T, 0, 1, 10).targetTick(T.plusSeconds(10))).isEqualTo(1);
        assertThat(new AccelerationClock(T, 0, 10, 10).targetTick(T.plusSeconds(1))).isEqualTo(1);
        assertThatThrownBy(() -> new AccelerationClock(T, 0, 0, 10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccelerationClock.validate(61)).isInstanceOf(BusinessException.class);

        long at = c.targetTick(T.plusSeconds(10));   // 600초(60틱)
        AccelerationClock slower = c.withAcceleration(10, T.plusSeconds(10), at);
        assertThat(slower.targetTick(T.plusSeconds(10))).isEqualTo(at);
        assertThat(slower.targetTick(T.plusSeconds(11))).isEqualTo(at + 1);
        AccelerationClock resumed = c.rebase(T.plusSeconds(500), 60);
        assertThat(resumed.targetTick(T.plusSeconds(500))).isEqualTo(60);
        assertThat(resumed.targetTick(T.minusSeconds(5))).isEqualTo(60);
    }
}
