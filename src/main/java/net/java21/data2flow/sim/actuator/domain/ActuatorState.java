package net.java21.data2flow.sim.actuator.domain;

import java.util.Map;
import java.util.TreeMap;

/**
 * 가상 장비의 보고 상태(reported, ACT {@code device_shadows.reported} 모양 {@code {capability:{attr:value}}})와 버전.
 * 체크포인트·{@code devices.actuator_state}에 그대로 저장한다.
 */
public final class ActuatorState {

    /** 기능 → 속성 → 값 */
    public TreeMap<String, TreeMap<String, Object>> capabilities = new TreeMap<>();
    /** 상태 버전. 바뀔 때마다 1씩 오른다(BR-ACT-05: 버전이 클 때만 반영) */
    public long version;
    /** 전원을 다시 켤 때 쓰는 마지막 냉난방 모드 */
    public String lastMode = "cool";

    public ActuatorState() {
    }

    public Object get(String capability, String attribute) {
        Map<String, Object> c = capabilities.get(capability);
        return c == null ? null : c.get(attribute);
    }

    public void put(String capability, String attribute, Object value) {
        capabilities.computeIfAbsent(capability, k -> new TreeMap<>()).put(attribute, value);
    }

    public boolean has(String capability) {
        return capabilities.containsKey(capability);
    }

    public boolean on() {
        Object v = get("Switch", "on");
        if (v instanceof Boolean b) {
            return b;
        }
        return !has("Switch");
    }

    public double number(String capability, String attribute, double fallback) {
        Object v = get(capability, attribute);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public String text(String capability, String attribute, String fallback) {
        Object v = get(capability, attribute);
        return v == null ? fallback : String.valueOf(v);
    }

    public ActuatorState copy() {
        ActuatorState s = new ActuatorState();
        capabilities.forEach((k, v) -> s.capabilities.put(k, new TreeMap<>(v)));
        s.version = version;
        s.lastMode = lastMode;
        return s;
    }

    /** 보고용 복사본({@code {capability:{attr:value}}}) */
    public Map<String, Map<String, Object>> reported() {
        Map<String, Map<String, Object>> m = new TreeMap<>();
        capabilities.forEach((k, v) -> m.put(k, new TreeMap<>(v)));
        return m;
    }

    /** 기능 목록으로 초기 상태(모두 OFF, SIM-03.01 "초기 상태 OFF") */
    public static ActuatorState initial(Iterable<String> capabilities) {
        ActuatorState s = new ActuatorState();
        for (String c : capabilities) {
            switch (c) {
                case "Switch" -> s.put(c, "on", false);
                case "Thermostat" -> {
                    s.put(c, "mode", "off");
                    s.put(c, "targetTemperature", 24.0);
                }
                case "FanSpeed" -> {
                    s.put(c, "level", 2);
                    s.put(c, "auto", false);
                }
                case "Ventilation" -> {
                    s.put(c, "mode", "off");
                    s.put(c, "level", 1);
                }
                case "Dimmer" -> s.put(c, "level", 100);
                case "Lock" -> {
                    s.put(c, "locked", true);
                    s.put(c, "battery", 100.0);
                }
                case "custom.Humidifier", "custom.Dehumidifier" -> s.put(c, "targetHumidity", 50);
                default -> s.capabilities.put(c, new TreeMap<>());
            }
        }
        return s;
    }
}
