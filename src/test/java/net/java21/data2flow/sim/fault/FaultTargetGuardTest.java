package net.java21.data2flow.sim.fault;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.fault.domain.FaultValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultTargetGuardTest {

    @Test
    @DisplayName("[SIM-05.01][AT-SIM-11.1][TC-SIM-060] 강도 범위 밖(DUPLICATE ×9, DROPOUT 1.5, 기간 10초) → SIM_PROPERTY_OUT_OF_RANGE")
    void outOfRange() {
        assertThatThrownBy(() -> FaultValidator.validate(FaultKind.DUPLICATE, FaultSpec.DEVICE, Map.of("factor", 9), 600))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE);
        assertThatThrownBy(() -> FaultValidator.validate(FaultKind.DROPOUT, FaultSpec.DEVICE, Map.of("ratio", 1.5), 600))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FaultValidator.validate(FaultKind.STUCK, FaultSpec.DEVICE, Map.of(), 10))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FaultValidator.validate(FaultKind.GATEWAY_DOWN, FaultSpec.DEVICE, Map.of(), 600))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FaultValidator.validate(FaultKind.STUCK, FaultSpec.GATEWAY, Map.of(), 600))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FaultValidator.validate(FaultKind.STUCK, FaultSpec.DEVICE, Map.of("value", "x"), 600))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(FaultKind.class)
    @DisplayName("[SIM-05.01][AT-SIM-11.1][TC-SIM-059] 12종 모두 기본 강도로 주입할 수 있다")
    void defaults(FaultKind kind) {
        Map<String, Object> params = switch (kind) {
            case SPIKE -> Map.of("magnitude", 20, "count", 3);
            case DRIFT -> Map.of("perHour", 0.5);
            case INTERMITTENT -> Map.of("onSec", 60, "offSec", 60);
            case BATTERY_DRAIN -> Map.of("pctPerHour", 10);
            case DUPLICATE -> Map.of("factor", 2);
            case REORDER -> Map.of("windowSec", 300);
            case DELAY -> Map.of("delaySec", 7200);
            case MALFORMED -> Map.of("ratio", 0.1);
            default -> Map.of();
        };
        String target = kind == FaultKind.GATEWAY_DOWN ? FaultSpec.GATEWAY : FaultSpec.DEVICE;
        assertThatCode(() -> FaultValidator.validate(kind, target, params, 1800)).doesNotThrowAnyException();
        assertThat(kind.sensor()).isEqualTo(kind.ordinal() < 7);
    }
}
