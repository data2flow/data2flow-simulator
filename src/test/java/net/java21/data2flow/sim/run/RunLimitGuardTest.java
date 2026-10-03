package net.java21.data2flow.sim.run;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.run.domain.RunLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RunLimitGuardTest {

    @Test
    @DisplayName("[SIM-11.01][AT-SIM-08.4][TC-SIM-114] x60에서 기기 101대 → SIM_ACCELERATION_LIMIT(허용 가속 제안), x30이면 허용, 100대는 x60 허용")
    void acceleration() {
        assertThatCode(() -> RunLimits.checkAcceleration(100, 60, 100)).doesNotThrowAnyException();
        assertThatCode(() -> RunLimits.checkAcceleration(101, 30, 100)).doesNotThrowAnyException();
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> RunLimits.checkAcceleration(101, 60, 100));
        assertThat(e.getErrorCode()).isEqualTo(SimErrorCode.SIM_ACCELERATION_LIMIT);
        assertThat(e.getHeaders()).containsEntry(RunLimits.ALLOWED_HEADER, "59");
        assertThat(RunLimits.allowedAcceleration(500, 100)).isEqualTo(12);
        assertThat(RunLimits.allowedAcceleration(10_000, 100)).isEqualTo(1);
        assertThat(RunLimits.allowedAcceleration(0, 100)).isEqualTo(60);
    }
}
