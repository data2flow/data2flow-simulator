package net.java21.data2flow.sim.catalog;

import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.PropertyDef;
import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyInheritanceResolverTest {

    @Test
    @DisplayName("[SIM-09.02][AT-SIM-03.1][TC-SIM-094] 프로필에 냉방 능력 5.0만 저장 → 카탈로그 소비 전력 변경은 프로필에 반영, 냉방 능력은 유지")
    void inheritance() {
        DeviceTypeDef aircon = BuiltinCatalog.type("aircon").orElseThrow();
        Map<String, Object> profile = Map.of("coolingCapacityKw", 5.0);
        Map<String, Object> eff = PropertyResolver.effective(aircon, profile, Map.of());
        assertThat(eff.get("coolingCapacityKw")).isEqualTo(5.0);
        assertThat(eff.get("ratedPowerW")).isEqualTo(1200.0);

        // 카탈로그 기본값이 바뀐 유형(소비 전력 1.2kW → 1.0kW)
        List<PropertyDef> defs = new ArrayList<>(aircon.propertyDefs());
        defs.replaceAll(d -> d.key().equals("ratedPowerW") ? new PropertyDef(d.key(), d.name(), d.type(), d.unit(), d.min(), d.max(),
                null, 1000.0, d.description()) : d);
        DeviceTypeDef changed = new DeviceTypeDef(aircon.key(), aircon.name(), aircon.category(), aircon.metrics(), aircon.capabilities(),
                defs, aircon.physicsEffects(), null, aircon.defaultPayloadFormat(), aircon.defaultReportIntervalSec(), false);
        Map<String, Object> after = PropertyResolver.effective(changed, profile, Map.of());
        assertThat(after.get("ratedPowerW")).isEqualTo(1000.0);
        assertThat(after.get("coolingCapacityKw")).isEqualTo(5.0);

        List<PropertyResolver.Resolved> withDevice = PropertyResolver.resolve(aircon, profile, Map.of("coolingCapacityKw", 3.0));
        assertThat(withDevice.stream().filter(r -> r.key().equals("coolingCapacityKw")).findFirst().orElseThrow().origin())
                .isEqualTo(PropertyResolver.DEVICE);
    }

    @Test
    @DisplayName("[SIM-09.02][AT-SIM-03.1][TC-SIM-094] 기기 override null → 출처 PROFILE로 복귀, 바꾼 값만 저장")
    void revert() {
        DeviceTypeDef aircon = BuiltinCatalog.type("aircon").orElseThrow();
        Map<String, Object> device = PropertyResolver.merge(Map.of("coolingCapacityKw", 3.0, "noiseDb", 35.0),
                new HashMap<>(java.util.Collections.singletonMap("coolingCapacityKw", null)));
        assertThat(device).containsOnlyKeys("noiseDb");
        List<PropertyResolver.Resolved> r = PropertyResolver.resolve(aircon, Map.of("coolingCapacityKw", 5.0), device);
        assertThat(r.stream().filter(x -> x.key().equals("coolingCapacityKw")).findFirst().orElseThrow().origin())
                .isEqualTo(PropertyResolver.PROFILE);
        assertThat(r.stream().filter(x -> x.key().equals("ratedPowerW")).findFirst().orElseThrow().origin())
                .isEqualTo(PropertyResolver.CATALOG);
    }
}
