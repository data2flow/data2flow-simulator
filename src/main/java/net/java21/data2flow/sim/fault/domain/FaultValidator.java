package net.java21.data2flow.sim.fault.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.common.SimErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 장애 강도 범위 검사(API-SIM-20 표, TC-SIM-060). 범위 밖이면 {@code SIM_PROPERTY_OUT_OF_RANGE} */
public final class FaultValidator {

    public static final long MIN_DURATION_SEC = 60;
    public static final long MAX_DURATION_SEC = 86_400;

    private FaultValidator() {
    }

    public static void validate(FaultKind kind, String targetType, Map<String, Object> params, long durationSec) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (durationSec < MIN_DURATION_SEC || durationSec > MAX_DURATION_SEC) {
            errors.add(out("durationSec", MIN_DURATION_SEC + "~" + MAX_DURATION_SEC));
        }
        if (kind == FaultKind.GATEWAY_DOWN && !FaultSpec.GATEWAY.equals(targetType)) {
            errors.add(out("targetType", "GATEWAY"));
        }
        if (kind != FaultKind.GATEWAY_DOWN && FaultSpec.GATEWAY.equals(targetType) && kind.sensor()) {
            errors.add(out("targetType", "DEVICE"));
        }
        Map<String, Object> p = params == null ? Map.of() : params;
        switch (kind) {
            case STUCK -> optionalFinite(errors, p, "value");
            case SPIKE -> {
                range(errors, p, "magnitude", -10_000, 10_000, true);
                range(errors, p, "count", 1, 1_000, true);
            }
            case DRIFT -> range(errors, p, "perHour", -1_000, 1_000, true);
            case DROPOUT -> range(errors, p, "ratio", 0, 1, false);
            case INTERMITTENT -> {
                range(errors, p, "onSec", 1, 86_400, true);
                range(errors, p, "offSec", 1, 86_400, true);
            }
            case BATTERY_DRAIN -> range(errors, p, "pctPerHour", 0, 100, true);
            case OUT_OF_RANGE -> optionalFinite(errors, p, "value");
            case DUPLICATE -> range(errors, p, "factor", 2, 5, true);
            case REORDER -> range(errors, p, "windowSec", 1, 3_600, true);
            case DELAY -> range(errors, p, "delaySec", 1, 86_400, true);
            case MALFORMED -> range(errors, p, "ratio", 0, 1, true);
            case GATEWAY_DOWN -> {
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE, errors);
        }
    }

    private static void range(List<FieldErrorDetail> errors, Map<String, Object> p, String key, double min, double max,
                              boolean required) {
        Object v = p.get(key);
        if (v == null) {
            if (required) {
                errors.add(out("params." + key, fmt(min) + "~" + fmt(max)));
            }
            return;
        }
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max) {
            errors.add(out("params." + key, fmt(min) + "~" + fmt(max)));
        }
    }

    private static void optionalFinite(List<FieldErrorDetail> errors, Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v != null && (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()))) {
            errors.add(out("params." + key, "number"));
        }
    }

    private static FieldErrorDetail out(String field, String allowed) {
        return new FieldErrorDetail(field, SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(), "허용 범위: " + allowed);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
