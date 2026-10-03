package net.java21.data2flow.sim.run;

import net.java21.data2flow.sim.run.domain.ThrottleController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimThrottleTest {

    @Test
    @DisplayName("[SIM-11.02][AT-SIM-08.1][TC-SIM-117] 설계 200 msg/s → 가상 상한 100 msg/s, 넘으면 가속 하향(x60 → x30), 1분 동안 여유면 복귀")
    void throttle() {
        ThrottleController t = new ThrottleController(200, 0.5);
        assertThat(t.limit(0)).isEqualTo(100);
        assertThat(t.limit(150)).isEqualTo(50);
        assertThat(t.limit(500)).isEqualTo(1);
        ThrottleController.Decision down = t.decide(0, 60, 60, 150, 0);
        assertThat(down.changed()).isTrue();
        assertThat(down.to()).isEqualTo(30);
        assertThat(down.reason()).isEqualTo("VIRTUAL_RATE_LIMIT");
        assertThat(t.decide(1_000, 30, 60, 80, 0).changed()).isFalse();
        assertThat(t.decide(2_000, 30, 60, 20, 0).changed()).isFalse();
        assertThat(t.decide(30_000, 30, 60, 20, 0).changed()).isFalse();
        ThrottleController.Decision up = t.decide(62_000, 30, 60, 20, 0);
        assertThat(up.changed()).isTrue();
        assertThat(up.to()).isEqualTo(60);
        assertThat(t.decide(70_000, 1, 1, 500, 0).changed()).isFalse();
        t.record(0, 40);
        t.record(500, 30);
        assertThat(t.currentWindow()).isEqualTo(70);
        t.record(1500, 5);
        assertThat(t.currentWindow()).isEqualTo(5);
    }
}
