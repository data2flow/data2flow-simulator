package net.java21.data2flow.sim.actuator;

import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorCatalogTest {

    @Test
    @DisplayName("[SIM-03.01][AT-SIM-01.5][TC-SIM-029] 장비 11종 각각 기능 목록·기본 상태(OFF)·기본 특성이 있다")
    void elevenActuators() {
        assertThat(BuiltinCatalog.ACTUATOR_KEYS).hasSize(11);
        for (String key : BuiltinCatalog.ACTUATOR_KEYS) {
            DeviceTypeDef t = BuiltinCatalog.type(key).orElseThrow();
            assertThat(t.actuator()).as(key).isTrue();
            assertThat(t.capabilities()).as(key).isNotEmpty();
            assertThat(t.propertyDefs()).as(key).isNotEmpty();
            assertThat(t.propertyDefs()).allMatch(p -> p.accepts(p.defaultValue()));
            ActuatorState s = ActuatorState.initial(t.capabilities());
            if (t.capabilities().contains("Switch")) {
                assertThat(s.on()).as(key + " 초기 OFF").isFalse();
            }
        }
        DeviceTypeDef aircon = BuiltinCatalog.type(BuiltinCatalog.AIRCON).orElseThrow();
        assertThat(aircon.capabilities()).containsExactly("Switch", "Thermostat", "FanSpeed");
        assertThat(aircon.property("coolingCapacityKw").orElseThrow().defaultValue()).isEqualTo(3.5);
        assertThat(aircon.property("setpointMin").orElseThrow().defaultValue()).isEqualTo(18.0);
        assertThat(aircon.property("setpointMax").orElseThrow().defaultValue()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("[SIM-09.01][AT-SIM-01.5][TC-SIM-090] 카탈로그 기본 유형 전체가 설정 없이 기본값만으로 정의되어 있다(센서는 측정 항목, 키트는 유형 참조)")
    void catalogDefaults() {
        for (DeviceTypeDef t : BuiltinCatalog.types()) {
            assertThat(t.defaultReportIntervalSec()).as(t.key()).isBetween(5, 86_400);
            if (!t.actuator()) {
                assertThat(t.metrics()).as(t.key()).isNotEmpty();
            }
            assertThat(t.propertyDefs()).allMatch(p -> p.accepts(p.defaultValue()));
        }
        BuiltinCatalog.kits().forEach(k -> k.items().forEach(i -> assertThat(BuiltinCatalog.type(i.typeKey())).isPresent()));
        assertThat(BuiltinCatalog.kit(BuiltinCatalog.KIT_CLASSROOM).orElseThrow().deviceCount()).isEqualTo(7);
    }
}
