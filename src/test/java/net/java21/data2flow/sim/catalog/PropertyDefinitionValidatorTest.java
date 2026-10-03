package net.java21.data2flow.sim.catalog;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.PropertyDef;
import net.java21.data2flow.sim.catalog.domain.PropertyValidator;
import net.java21.data2flow.sim.common.SimErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class PropertyDefinitionValidatorTest {

    static final DeviceTypeDef AIRCON = BuiltinCatalog.type("aircon").orElseThrow();

    @ParameterizedTest
    @CsvSource({"coolingCapacityKw,0.5,true", "coolingCapacityKw,20,true", "coolingCapacityKw,25,false",
            "coolingCapacityKw,0.4,false", "noiseDb,40,true", "noiseDb,-1,false"})
    @DisplayName("[SIM-09.03][AT-SIM-03.2][TC-SIM-097] 정의(타입·단위·범위)로 검증: 냉방 능력 0.5~20kW, 25kW 거부")
    void ranges(String key, double value, boolean ok) {
        if (ok) {
            assertThatCode(() -> PropertyValidator.validate(AIRCON, Map.of(key, value), "overrides.")).doesNotThrowAnyException();
        } else {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> PropertyValidator.validate(AIRCON, Map.of(key, value), "overrides."));
            assertThat(e.getErrorCode()).isEqualTo(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE);
            assertThat(e.getErrors().get(0).field()).isEqualTo("overrides." + key);
            assertThat(e.getErrors().get(0).message()).contains("허용 범위");
        }
    }

    @ParameterizedTest
    @CsvSource({"windowSide,true", "nope,false"})
    @DisplayName("[SIM-09.03][AT-SIM-03.2][TC-SIM-097] 타입 불일치·모르는 특성 거부")
    void types(String key, boolean known) {
        DeviceTypeDef lux = BuiltinCatalog.type("lux-sensor").orElseThrow();
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> PropertyValidator.validate(lux, Map.of(key, "yes"), ""));
        assertThat(e.getErrors().get(0).code()).isEqualTo(known ? "SIM_PROPERTY_OUT_OF_RANGE" : "UNKNOWN_PROPERTY");
        PropertyDef choice = PropertyDef.choice("mode", "모드", List.of("A", "B"), "A", "");
        assertThat(choice.accepts("A")).isTrue();
        assertThat(choice.accepts("C")).isFalse();
        assertThat(choice.allowed()).isEqualTo("A|B");
        assertThat(PropertyDef.bool("b", "b", true, "").allowed()).isEqualTo("true|false");
        assertThat(AIRCON.property("coolingCapacityKw").orElseThrow().allowed()).isEqualTo("0.5~20 kW");
    }
}
