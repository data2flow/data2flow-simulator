package net.java21.data2flow.sim.sensor.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * 독립 생성기 설정(SIM-02.03, API-SIM-09 생성기 파라미터).
 *
 * <table>
 *   <tr><td>FIXED</td><td>{@code {value}}</td></tr>
 *   <tr><td>RANDOM_WALK</td><td>{@code {start, min, max, stepStd}}</td></tr>
 *   <tr><td>DIURNAL</td><td>{@code {mean, amplitude, peakHour}}</td></tr>
 *   <tr><td>SCHEDULE</td><td>{@code {segments:[{from:"09:00", to:"12:00", value}], defaultValue}}</td></tr>
 *   <tr><td>EVENT</td><td>{@code {ratePerHour, durationSec, activeValue, idleValue}}</td></tr>
 * </table>
 */
public record GeneratorSpec(String kind, Map<String, Object> params) {

    public static final String FIXED = "FIXED";
    public static final String RANDOM_WALK = "RANDOM_WALK";
    public static final String DIURNAL = "DIURNAL";
    public static final String SCHEDULE = "SCHEDULE";
    public static final String EVENT = "EVENT";

    public GeneratorSpec {
        params = params == null ? Map.of() : new TreeMap<>(params);
    }

    public static GeneratorSpec fixed(double value) {
        return new GeneratorSpec(FIXED, Map.of("value", value));
    }

    public static GeneratorSpec randomWalk(double start, double min, double max, double stepStd) {
        return new GeneratorSpec(RANDOM_WALK, Map.of("start", start, "min", min, "max", max, "stepStd", stepStd));
    }

    public static GeneratorSpec diurnal(double mean, double amplitude, double peakHour) {
        return new GeneratorSpec(DIURNAL, Map.of("mean", mean, "amplitude", amplitude, "peakHour", peakHour));
    }

    public static GeneratorSpec event(double ratePerHour, double durationSec, double activeValue, double idleValue) {
        return new GeneratorSpec(EVENT, Map.of("ratePerHour", ratePerHour, "durationSec", durationSec,
                "activeValue", activeValue, "idleValue", idleValue));
    }

    public double number(String key, double fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean has(String key) {
        return params.get(key) != null;
    }
}
