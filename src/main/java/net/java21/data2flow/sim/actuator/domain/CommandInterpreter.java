package net.java21.data2flow.sim.actuator.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 표준 기능 명령 해석(SIM-03.02, ACT domain-model "표준 기능 요약"). 명령은 토글이 아니라 <b>목표 상태 설정</b>({@code set})이라 여러 번
 * 적용해도 결과가 같다(ACT-02.04). 실제 장비처럼 쓰기 편한 별칭({@code on}, {@code off}, {@code setTargetTemperature}, {@code setMode},
 * {@code setLevel}, {@code lock}, {@code unlock})도 받는다.
 *
 * <p>가상 장비 규칙: 냉난방 모드를 켜거나 설정 온도만 바꿔도 전원이 켜지고(마지막 모드, 기본 cool), Switch를 끄면 모드가 off가 된다.
 * 조명·히터 Dimmer를 0보다 크게 하면 켜지고 0이면 꺼진다. 선풍기 FanSpeed 0은 끔.
 */
public final class CommandInterpreter {

    private static final Set<String> THERMOSTAT_MODES = Set.of("off", "cool", "heat", "dry", "fan", "auto");
    private static final Set<String> VENT_MODES = Set.of("off", "on", "auto");

    private CommandInterpreter() {
    }

    /**
     * 명령을 검사하고 새 상태를 만든다(원래 상태는 바꾸지 않음).
     *
     * @param typeKey    장비 유형 키
     * @param props      유효 특성(설정 온도 범위 등)
     * @param current    현재 상태
     * @param capability 기능
     * @param command    명령
     * @param args       인자
     * @throws BusinessException 400 INVALID_REQUEST(지원하지 않는 기능·명령, 범위 밖 값)
     */
    public static ActuatorState apply(String typeKey, Map<String, Object> props, ActuatorState current, String capability,
                                      String command, Map<String, Object> args) {
        if (capability == null || !current.has(capability)) {
            throw invalid("capability", "UNSUPPORTED_CAPABILITY", "이 장비가 지원하지 않는 기능입니다: " + capability);
        }
        Map<String, Object> a = normalize(capability, command, args == null ? Map.of() : args);
        ActuatorState next = current.copy();
        switch (capability) {
            case "Switch" -> {
                boolean on = bool(a, "on");
                next.put("Switch", "on", on);
                couplePower(next, on);
            }
            case "Thermostat" -> thermostat(props, next, a);
            case "FanSpeed" -> {
                if (a.containsKey("level")) {
                    int level = integer(a, "level", 0, 3);
                    next.put("FanSpeed", "level", level);
                    if ("fan".equals(typeKey)) {
                        next.put("Switch", "on", level > 0);
                    }
                }
                if (a.containsKey("auto")) {
                    next.put("FanSpeed", "auto", bool(a, "auto"));
                }
                requireAny(a, "level", "auto");
            }
            case "Ventilation" -> {
                requireAny(a, "mode", "level");
                if (a.containsKey("level")) {
                    next.put("Ventilation", "level", integer(a, "level", 1, 3));
                }
                if (a.containsKey("mode")) {
                    String mode = choice(a, "mode", VENT_MODES);
                    next.put("Ventilation", "mode", mode);
                    if (next.has("Switch")) {
                        next.put("Switch", "on", !"off".equals(mode));
                    }
                }
            }
            case "Dimmer" -> {
                int level = integer(a, "level", 0, 100);
                next.put("Dimmer", "level", level);
                if (next.has("Switch")) {
                    next.put("Switch", "on", level > 0);
                }
            }
            case "Lock" -> next.put("Lock", "locked", bool(a, "locked"));
            case "custom.Humidifier", "custom.Dehumidifier" ->
                    next.put(capability, "targetHumidity", integer(a, "targetHumidity", 30, 70));
            default -> throw invalid("capability", "UNSUPPORTED_CAPABILITY", "지원하지 않는 기능입니다: " + capability);
        }
        return next;
    }

    private static void thermostat(Map<String, Object> props, ActuatorState next, Map<String, Object> a) {
        requireAny(a, "mode", "targetTemperature");
        if (a.containsKey("targetTemperature")) {
            double min = number(props.get("setpointMin"), 18);
            double max = number(props.get("setpointMax"), 30);
            Object v = a.get("targetTemperature");
            if (!(v instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max) {
                throw invalid("args.targetTemperature", "OUT_OF_RANGE", "설정 온도 범위: " + fmt(min) + "~" + fmt(max) + "℃");
            }
            next.put("Thermostat", "targetTemperature", Math.round(n.doubleValue() * 2) / 2.0);
        }
        String mode = a.containsKey("mode") ? choice(a, "mode", THERMOSTAT_MODES) : null;
        if (mode == null && "off".equals(next.text("Thermostat", "mode", "off"))) {
            mode = next.lastMode == null ? "cool" : next.lastMode;   // 설정 온도만 바꿔도 켠다
        }
        if (mode != null) {
            next.put("Thermostat", "mode", mode);
            if (!"off".equals(mode)) {
                next.lastMode = mode;
            }
            if (next.has("Switch")) {
                next.put("Switch", "on", !"off".equals(mode));
            }
        }
    }

    private static void couplePower(ActuatorState next, boolean on) {
        if (next.has("Thermostat")) {
            next.put("Thermostat", "mode", on ? (next.lastMode == null ? "cool" : next.lastMode) : "off");
        }
        if (next.has("Ventilation")) {
            String mode = next.text("Ventilation", "mode", "off");
            if (on && "off".equals(mode)) {
                next.put("Ventilation", "mode", "on");
            } else if (!on) {
                next.put("Ventilation", "mode", "off");
            }
        }
    }

    /** 별칭을 표준 {@code set} 인자로 바꾼다 */
    static Map<String, Object> normalize(String capability, String command, Map<String, Object> args) {
        String c = command == null ? "set" : command;
        Map<String, Object> a = new LinkedHashMap<>(args);
        switch (c) {
            case "set" -> {
                return a;
            }
            case "on" -> a.put("on", true);
            case "off" -> a.put("on", false);
            case "lock" -> a.put("locked", true);
            case "unlock" -> a.put("locked", false);
            case "setTargetTemperature" -> rename(a, "value", "targetTemperature");
            case "setMode" -> rename(a, "value", "mode");
            case "setLevel" -> rename(a, "value", "level");
            case "setTargetHumidity" -> rename(a, "value", "targetHumidity");
            default -> throw invalid("command", "UNSUPPORTED_COMMAND", capability + "에 없는 명령입니다: " + c);
        }
        return a;
    }

    private static void rename(Map<String, Object> a, String from, String to) {
        if (!a.containsKey(to) && a.containsKey(from)) {
            a.put(to, a.remove(from));
        }
    }

    private static void requireAny(Map<String, Object> a, String... keys) {
        for (String k : keys) {
            if (a.containsKey(k)) {
                return;
            }
        }
        throw invalid("args", "NotEmpty", "인자가 필요합니다: " + String.join(" 또는 ", keys));
    }

    private static boolean bool(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        throw invalid("args." + key, "TYPE_MISMATCH", key + "는 true·false입니다");
    }

    private static int integer(Map<String, Object> a, String key, int min, int max) {
        Object v = a.get(key);
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.intValue() >= min && n.intValue() <= max) {
            return n.intValue();
        }
        throw invalid("args." + key, "OUT_OF_RANGE", key + " 범위: " + min + "~" + max);
    }

    private static String choice(Map<String, Object> a, String key, Set<String> values) {
        Object v = a.get(key);
        String s = v == null ? null : String.valueOf(v).toLowerCase();
        if (s == null || !values.contains(s)) {
            throw invalid("args." + key, "OUT_OF_RANGE", key + " 값: " + String.join("|", values.stream().sorted().toList()));
        }
        return s;
    }

    static double number(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
