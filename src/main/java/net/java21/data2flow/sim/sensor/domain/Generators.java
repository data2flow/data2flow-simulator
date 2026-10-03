package net.java21.data2flow.sim.sensor.domain;

import net.java21.data2flow.sim.random.SimRandom;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 독립 생성기 5종(SIM-02.03). 난수는 (시드, 기기 키, 측정 키, 순번)으로 정한다(BR-SIM-07).
 * EVENT는 매 틱 {@link #tick}으로 포아송 과정을 진행하고, 나머지는 보고할 때 {@link #sample}로 값을 만든다.
 */
public final class Generators {

    private Generators() {
    }

    /** EVENT 생성기를 한 틱 진행한다(그 밖의 종류는 아무것도 하지 않음) */
    public static void tick(GeneratorSpec spec, GeneratorState state, long seed, String deviceKey, String metric,
                            long tickIndex, Instant tickEnd, double dtSec) {
        if (!GeneratorSpec.EVENT.equals(spec.kind())) {
            return;
        }
        long now = tickEnd.getEpochSecond();
        if (state.activeUntil > now) {
            return;
        }
        state.activeUntil = 0;
        double rate = Math.max(0, spec.number("ratePerHour", 1));
        double p = 1 - Math.exp(-rate * dtSec / 3600.0);
        if (SimRandom.bernoulli(p, seed, deviceKey, metric, "event", tickIndex)) {
            state.activeUntil = now + Math.max(1, (long) spec.number("durationSec", 60));
            state.events++;
        }
    }

    /**
     * 보고 시점의 값.
     *
     * @param sampleIndex 이 측정 항목의 보고 순번(랜덤 워크 걸음 번호)
     */
    public static double sample(GeneratorSpec spec, GeneratorState state, Instant at, ZoneId zone, long seed, String deviceKey,
                                String metric, long sampleIndex) {
        return switch (spec.kind()) {
            case GeneratorSpec.FIXED -> spec.number("value", 0);
            case GeneratorSpec.RANDOM_WALK -> randomWalk(spec, state, seed, deviceKey, metric, sampleIndex);
            case GeneratorSpec.DIURNAL -> diurnal(spec, at, zone);
            case GeneratorSpec.SCHEDULE -> schedule(spec, at, zone);
            case GeneratorSpec.EVENT -> state.activeUntil > at.getEpochSecond()
                    ? spec.number("activeValue", 1) : spec.number("idleValue", 0);
            default -> throw new IllegalArgumentException("모르는 생성기입니다: " + spec.kind());
        };
    }

    static double randomWalk(GeneratorSpec spec, GeneratorState state, long seed, String deviceKey, String metric, long index) {
        double min = spec.number("min", Double.NEGATIVE_INFINITY);
        double max = spec.number("max", Double.POSITIVE_INFINITY);
        if (!state.initialized) {
            state.current = clamp(spec.number("start", (min + max) / 2), min, max);
            state.initialized = true;
            return state.current;
        }
        double step = spec.number("stepStd", 0.1) * SimRandom.gaussian(seed, deviceKey, metric, "walk", index);
        double next = state.current + step;
        // 경계에서 반사해 범위를 벗어나지 않는다(TC-SIM-019)
        for (int i = 0; i < 4 && (next < min || next > max); i++) {
            next = next < min ? 2 * min - next : 2 * max - next;
        }
        state.current = clamp(next, min, max);
        return state.current;
    }

    /** 평균 + 진폭·cos(2π(h − 피크)/24). 피크 시각에 최대, 12시간 뒤 최소(TC-SIM-017) */
    static double diurnal(GeneratorSpec spec, Instant at, ZoneId zone) {
        ZonedDateTime local = at.atZone(zone);
        double hour = local.getHour() + local.getMinute() / 60.0 + local.getSecond() / 3600.0;
        return spec.number("mean", 0) + spec.number("amplitude", 0)
                * Math.cos(2 * Math.PI * (hour - spec.number("peakHour", 15)) / 24.0);
    }

    /** 시간표: {@code segments:[{from:"09:00", to:"18:00", value}]}, 구간 밖이면 {@code defaultValue} */
    static double schedule(GeneratorSpec spec, Instant at, ZoneId zone) {
        LocalTime t = at.atZone(zone).toLocalTime();
        Object segments = spec.params().get("segments");
        if (segments instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> seg) {
                    LocalTime from = LocalTime.parse(String.valueOf(seg.get("from")));
                    String toText = String.valueOf(seg.get("to"));
                    LocalTime to = "24:00".equals(toText) ? LocalTime.MAX : LocalTime.parse(toText);
                    boolean inside = from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
                    if (inside && seg.get("value") instanceof Number n) {
                        return n.doubleValue();
                    }
                }
            }
        }
        return spec.number("defaultValue", 0);
    }

    /** 생성기 설정 검증. 문제가 있으면 이유, 없으면 null */
    public static String validate(GeneratorSpec spec) {
        if (spec == null || spec.kind() == null) {
            return "kind";
        }
        return switch (spec.kind()) {
            case GeneratorSpec.FIXED -> spec.has("value") ? null : "value";
            case GeneratorSpec.RANDOM_WALK -> spec.number("min", 0) > spec.number("max", 0) || spec.number("stepStd", 0) < 0
                    ? "min<=max, stepStd>=0" : null;
            case GeneratorSpec.DIURNAL -> spec.number("peakHour", 0) < 0 || spec.number("peakHour", 0) > 24 ? "peakHour" : null;
            case GeneratorSpec.SCHEDULE -> validateSchedule(spec);
            case GeneratorSpec.EVENT -> spec.number("ratePerHour", -1) < 0 || spec.number("durationSec", -1) < 0
                    ? "ratePerHour, durationSec" : null;
            default -> "kind";
        };
    }

    private static String validateSchedule(GeneratorSpec spec) {
        Object segments = spec.params().get("segments");
        if (!(segments instanceof List<?> list)) {
            return "segments";
        }
        try {
            for (Object o : list) {
                Map<?, ?> seg = (Map<?, ?>) o;
                LocalTime.parse(String.valueOf(seg.get("from")));
                if (!"24:00".equals(String.valueOf(seg.get("to")))) {
                    LocalTime.parse(String.valueOf(seg.get("to")));
                }
                if (!(seg.get("value") instanceof Number)) {
                    return "segments[].value";
                }
            }
        } catch (RuntimeException e) {
            return "segments[].from/to";
        }
        return null;
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
