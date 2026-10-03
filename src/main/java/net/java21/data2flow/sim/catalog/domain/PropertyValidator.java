package net.java21.data2flow.sim.catalog.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.common.SimErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 특성 값 검증(SIM-09.03, BR-SIM-03): 정의(타입·범위)만으로 검사하고, 범위 밖이면 저장하지 않고 허용 범위를 알려 준다 */
public final class PropertyValidator {

    private PropertyValidator() {
    }

    /** 바꾼 값 묶음 검사. null 값(되돌리기)은 통과. 위반이 있으면 {@code SIM_PROPERTY_OUT_OF_RANGE} */
    public static void validate(DeviceTypeDef type, Map<String, Object> overrides, String fieldPrefix) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (value == null) {
                    return;
                }
                Optional<PropertyDef> def = type.property(key);
                if (def.isEmpty()) {
                    errors.add(new FieldErrorDetail(fieldPrefix + key, "UNKNOWN_PROPERTY", "알 수 없는 특성입니다"));
                } else if (!def.get().accepts(value)) {
                    errors.add(new FieldErrorDetail(fieldPrefix + key, SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(),
                            "허용 범위: " + def.get().allowed()));
                }
            });
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE, errors);
        }
    }
}
