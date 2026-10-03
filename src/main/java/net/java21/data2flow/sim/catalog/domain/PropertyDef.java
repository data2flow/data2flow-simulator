package net.java21.data2flow.sim.catalog.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 특성 정의(SIM-09.03, {@code device_types.property_defs[]}). 편집 폼은 이 정의만으로 만들고, 범위 밖 값은 저장하지 않는다(BR-SIM-03).
 *
 * @param key        특성 키
 * @param name       표시 이름(한국어 원문)
 * @param type       number, enum, boolean
 * @param unit       단위. 없으면 null
 * @param min        최솟값(number)
 * @param max        최댓값(number)
 * @param enumValues 선택지(enum)
 * @param defaultValue 카탈로그 기본값
 * @param description 설명
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PropertyDef(String key, String name, String type, String unit, Double min, Double max, List<String> enumValues,
                          @com.fasterxml.jackson.annotation.JsonProperty("default") Object defaultValue, String description) {

    public static final String NUMBER = "number";
    public static final String ENUM = "enum";
    public static final String BOOLEAN = "boolean";

    public static PropertyDef number(String key, String name, String unit, double min, double max, double defaultValue,
                                     String description) {
        return new PropertyDef(key, name, NUMBER, unit, min, max, null, defaultValue, description);
    }

    public static PropertyDef bool(String key, String name, boolean defaultValue, String description) {
        return new PropertyDef(key, name, BOOLEAN, null, null, null, null, defaultValue, description);
    }

    public static PropertyDef choice(String key, String name, List<String> values, String defaultValue, String description) {
        return new PropertyDef(key, name, ENUM, null, null, null, List.copyOf(values), defaultValue, description);
    }

    /** 이 정의에 맞는 값인가 */
    public boolean accepts(Object value) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case NUMBER -> value instanceof Number n && Double.isFinite(n.doubleValue())
                    && (min == null || n.doubleValue() >= min) && (max == null || n.doubleValue() <= max);
            case BOOLEAN -> value instanceof Boolean;
            case ENUM -> enumValues != null && enumValues.contains(String.valueOf(value));
            default -> false;
        };
    }

    /** 허용 범위 설명(오류 응답용) */
    public String allowed() {
        return switch (type) {
            case NUMBER -> (min == null ? "" : fmt(min)) + "~" + (max == null ? "" : fmt(max)) + (unit == null ? "" : " " + unit);
            case BOOLEAN -> "true|false";
            case ENUM -> String.join("|", enumValues == null ? List.of() : enumValues);
            default -> "";
        };
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
