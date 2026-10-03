package net.java21.data2flow.sim.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 특성 상속(SIM-09.02, BR-SIM-02): {@code 유효 값 = 기기.overrides[key] ?? 프로필.overrides[key] ?? 유형 기본값}.
 * 출처(origin)는 값을 준 단계다. 하위 단계에는 바꾼 값만 저장한다.
 */
public final class PropertyResolver {

    public static final String CATALOG = "CATALOG";
    public static final String PROFILE = "PROFILE";
    public static final String DEVICE = "DEVICE";

    private PropertyResolver() {
    }

    /**
     * @param key    특성 키
     * @param value  유효 값
     * @param origin CATALOG, PROFILE, DEVICE
     * @param def    정의(폼 생성용)
     */
    public record Resolved(String key, Object value, String origin, PropertyDef def) {
    }

    public static List<Resolved> resolve(DeviceTypeDef type, Map<String, Object> profileOverrides,
                                         Map<String, Object> deviceOverrides) {
        List<Resolved> list = new ArrayList<>();
        for (PropertyDef def : type.propertyDefs()) {
            Object device = deviceOverrides == null ? null : deviceOverrides.get(def.key());
            Object profile = profileOverrides == null ? null : profileOverrides.get(def.key());
            if (device != null) {
                list.add(new Resolved(def.key(), device, DEVICE, def));
            } else if (profile != null) {
                list.add(new Resolved(def.key(), profile, PROFILE, def));
            } else {
                list.add(new Resolved(def.key(), def.defaultValue(), CATALOG, def));
            }
        }
        return list;
    }

    /** 키 → 유효 값 */
    public static Map<String, Object> effective(DeviceTypeDef type, Map<String, Object> profileOverrides,
                                                Map<String, Object> deviceOverrides) {
        Map<String, Object> m = new LinkedHashMap<>();
        resolve(type, profileOverrides, deviceOverrides).forEach(r -> m.put(r.key(), r.value()));
        return m;
    }

    /**
     * 기존 바꾼 값에 변경을 합친다. 값이 null이면 그 키를 지워 상위 단계 값으로 되돌린다("기본값으로 되돌리기").
     */
    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> changes) {
        Map<String, Object> m = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (changes != null) {
            changes.forEach((k, v) -> {
                if (v == null) {
                    m.remove(k);
                } else {
                    m.put(k, v);
                }
            });
        }
        return m;
    }
}
