package net.java21.data2flow.sim.scenario.domain;

import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.KitDef;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.SpacePreset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 데모 프리셋 5종(SIM-04.05, API-SIM-18 presetKey). 프리셋은 공간 하나, 기기 목록, 시나리오 틀, 함께 만들 플로우 템플릿을 정한다.
 * core-api가 공간·기기 기준 정보를 만든 뒤 그 ID를 묶어(bindings) {@code POST /internal/sim/presets/{preset-key}/prepare}로 넘기면
 * simulator가 물리 설정·기기 설정·시나리오를 만든다.
 *
 * <p>이벤트 대상은 자리표시자로 적는다: {@code $space}(프리셋 공간), {@code $device:aircon#1}(유형의 n번째 기기),
 * {@code $gateway}(조직 기본 가상 게이트웨이).
 */
public final class DemoPresets {

    public static final String CLASSROOM_CROWDED = "classroom-crowded";
    public static final String HEATWAVE_AFTERNOON = "heatwave-afternoon";
    public static final String SENSOR_FAILURE = "sensor-failure";
    public static final String GATEWAY_OUTAGE = "gateway-outage";
    public static final String NIGHT_UNMANNED = "night-unmanned";

    /**
     * @param atSec    시작부터 초
     * @param untilSec 끝(초). 없으면 null
     */
    public record EventTemplate(String id, String track, int atSec, Integer untilSec, Map<String, Object> target,
                                Map<String, Object> params) {
    }

    /** 기대 결과 틀. deadlineSec은 시작부터 초(없으면 실행 끝) */
    public record ExpectationTemplate(String id, String kind, Map<String, Object> target, Map<String, Object> condition,
                                      Integer deadlineSec) {
    }

    /**
     * @param flowTemplates  함께 제안하는 플로우 템플릿(FLW-01.05)과 묶을 값
     * @param estimatedMinutes x60 실행 예상 시간(분)
     */
    public record PresetDef(String key, String name, String description, String spaceName, SpacePreset spacePreset,
                            List<KitDef.Item> devices, Instant simStartAt, int durationSec, OutdoorSpec outdoor,
                            List<EventTemplate> events, List<ExpectationTemplate> expectations,
                            Map<String, Map<String, Object>> flowTemplates, int estimatedMinutes) {

        public int deviceCount() {
            return devices.stream().mapToInt(KitDef.Item::count).sum();
        }
    }

    private static final Instant AUG10_00KST = Instant.parse("2026-08-09T15:00:00Z");

    private static final List<PresetDef> PRESETS = List.of(
            new PresetDef(CLASSROOM_CROWDED, "여름 강의실 과밀", "CO2 상승 → 환기 자동화", "데모 강의실(과밀)", SpacePreset.CLASSROOM,
                    List.of(new KitDef.Item(BuiltinCatalog.TH_SENSOR, 2, "온습도", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.CO2_SENSOR, 1, "CO2", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.PIR_SENSOR, 1, "재실", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.VENTILATOR, 1, "환기", "CONTROLS")),
                    AUG10_00KST.plusSeconds(9 * 3600), 6 * 3600, OutdoorSpec.diurnal(31, 24, 15, 60),
                    List.of(occupancy("class-1", 1800, 3 * 3600 + 1800, 30), occupancy("class-2", 4 * 3600, 6 * 3600 - 600, 30)),
                    List.of(new ExpectationTemplate("vent-on", "DEVICE_STATE_REACHED", Map.of("deviceId", "$device:ventilator#1"),
                                    Map.of("power", "ON"), 2 * 3600),
                            new ExpectationTemplate("vent-cycles", "CONTROL_COUNT_MAX", Map.of("deviceId", "$device:ventilator#1"),
                                    Map.of("max", 10), null)),
                    Map.of(BuiltinCatalog.FLOW_CO2_THEN_VENTILATE, Map.of("co2SensorIds", List.of("$device:co2-sensor#1"),
                            "ventilatorId", "$device:ventilator#1", "thresholdPpm", 1000, "durationMin", 5, "level", 3)), 6),
            new PresetDef(HEATWAVE_AFTERNOON, "폭염 오후", "온도 상승 → 에어컨 자동화", "데모 강의실(폭염)", SpacePreset.CLASSROOM,
                    List.of(new KitDef.Item(BuiltinCatalog.TH_SENSOR, 2, "온습도", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.CO2_SENSOR, 1, "CO2", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.PIR_SENSOR, 1, "재실", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.AIRCON, 1, "에어컨", "CONTROLS")),
                    AUG10_00KST.plusSeconds(12 * 3600), 24 * 3600, new OutdoorSpec(OutdoorSpec.DIURNAL,
                            new OutdoorSpec.Diurnal(35, 27, 15, 55, 10.0, 20.0), null, null),
                    List.of(occupancy("class", 3600, 6 * 3600, 15)),
                    List.of(new ExpectationTemplate("ac-on", "DEVICE_STATE_REACHED", Map.of("deviceId", "$device:aircon#1"),
                                    Map.of("power", "ON"), 3 * 3600),
                            new ExpectationTemplate("ac-cycles", "CONTROL_COUNT_MAX", Map.of("deviceId", "$device:aircon#1"),
                                    Map.of("max", 10), null)),
                    Map.of(BuiltinCatalog.FLOW_HOT_THEN_COOL, Map.of("temperatureSensorIds",
                            List.of("$device:th-sensor#1", "$device:th-sensor#2"), "airconId", "$device:aircon#1",
                            "thresholdC", 27, "durationMin", 5, "setpointC", 24)), 24),
            new PresetDef(SENSOR_FAILURE, "센서 고장", "값 멈춤 → 이상 탐지 알람", "데모 실습실(센서 고장)", SpacePreset.CLASSROOM,
                    List.of(new KitDef.Item(BuiltinCatalog.TH_SENSOR, 2, "온습도", "MEASURES")),
                    AUG10_00KST.plusSeconds(9 * 3600), 6 * 3600, OutdoorSpec.diurnal(30, 23, 15, 60),
                    List.of(occupancy("class", 1800, 5 * 3600, 20),
                            new EventTemplate("stuck", ScenarioEvent.FAULT, 2 * 3600, 3 * 3600,
                                    Map.of("targetType", "DEVICE", "targetIds", List.of("$device:th-sensor#1")),
                                    Map.of("kind", "STUCK", "params", Map.of()))),
                    List.of(new ExpectationTemplate("alarm", "ALARM_COUNT", Map.of("spaceId", "$space"), Map.of("op", "==", "value", 1),
                            null)),
                    Map.of(), 6),
            new PresetDef(GATEWAY_OUTAGE, "게이트웨이 장애", "일괄 오프라인 → 복구", "데모 층(게이트웨이)", SpacePreset.OFFICE,
                    List.of(new KitDef.Item(BuiltinCatalog.TH_SENSOR, 5, "온습도", "MEASURES")),
                    AUG10_00KST.plusSeconds(9 * 3600), 3 * 3600, OutdoorSpec.diurnal(29, 22, 15, 60),
                    List.of(new EventTemplate("gw-down", ScenarioEvent.FAULT, 3600, 3600 + 1800,
                            Map.of("targetType", "GATEWAY", "targetIds", List.of("$gateway")), Map.of("kind", "GATEWAY_DOWN", "params", Map.of()))),
                    List.of(new ExpectationTemplate("alarm", "ALARM_COUNT", Map.of("spaceId", "$space"), Map.of("op", "==", "value", 1),
                            null)),
                    Map.of(), 3),
            new PresetDef(NIGHT_UNMANNED, "야간 무인", "문 열림 → 보안 알림", "데모 강의실(야간)", SpacePreset.CLASSROOM,
                    List.of(new KitDef.Item(BuiltinCatalog.DOOR_SENSOR, 1, "출입문", "MEASURES"),
                            new KitDef.Item(BuiltinCatalog.PIR_SENSOR, 1, "재실", "MEASURES")),
                    AUG10_00KST.plusSeconds(22 * 3600), 8 * 3600, OutdoorSpec.diurnal(28, 22, 15, 70),
                    List.of(new EventTemplate("door", ScenarioEvent.OPENING, 2 * 3600, 2 * 3600 + 300, Map.of("spaceId", "$space"),
                                    Map.of("opening", "DOOR", "open", true)),
                            occupancy("intruder", 2 * 3600, 2 * 3600 + 600, 1)),
                    List.of(new ExpectationTemplate("alarm", "ALARM_COUNT", Map.of("spaceId", "$space"), Map.of("op", "==", "value", 1),
                            null)),
                    Map.of(), 8));

    private DemoPresets() {
    }

    public static List<PresetDef> all() {
        return PRESETS;
    }

    public static Optional<PresetDef> find(String key) {
        return PRESETS.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    private static EventTemplate occupancy(String id, int atSec, int untilSec, int count) {
        return new EventTemplate(id, ScenarioEvent.OCCUPANCY, atSec, untilSec, Map.of("spaceId", "$space"),
                Map.of("count", count, "activity", 2.0));
    }

    /**
     * 프리셋 틀을 실제 ID로 묶어 시나리오를 만든다.
     *
     * @param spaceId   프리셋 공간 ID
     * @param deviceIds 유형 키 → 그 유형 기기 ID(배치 순서)
     * @param gateway   조직 기본 가상 게이트웨이 EUI
     */
    public static Scenario materialize(PresetDef p, String name, long spaceId, Map<String, List<Long>> deviceIds, String gateway) {
        List<ScenarioEvent> events = new ArrayList<>();
        for (EventTemplate e : p.events()) {
            events.add(new ScenarioEvent(e.id(), e.track(), p.simStartAt().plusSeconds(e.atSec()),
                    e.untilSec() == null ? null : p.simStartAt().plusSeconds(e.untilSec()),
                    resolveMap(e.target(), spaceId, deviceIds, gateway), resolveMap(e.params(), spaceId, deviceIds, gateway)));
        }
        List<Expectation> expectations = new ArrayList<>();
        for (ExpectationTemplate x : p.expectations()) {
            expectations.add(new Expectation(x.id(), x.kind(), resolveMap(x.target(), spaceId, deviceIds, gateway), x.condition(),
                    x.deadlineSec() == null ? null : p.simStartAt().plusSeconds(x.deadlineSec())));
        }
        return new Scenario(name, List.of(spaceId), p.simStartAt(), p.durationSec(), 42L, false, p.outdoor(), events, expectations,
                p.key());
    }

    /** 플로우 템플릿 묶음 값(자리표시자 → ID 문자열) */
    public static Map<String, Map<String, Object>> flowBindings(PresetDef p, long spaceId, Map<String, List<Long>> deviceIds,
                                                                String gateway) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        p.flowTemplates().forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>(resolveMap(v, spaceId, deviceIds, gateway));
            m.put("spaceId", Long.toString(spaceId));
            out.put(k, m);
        });
        return out;
    }

    static Map<String, Object> resolveMap(Map<String, Object> m, long spaceId, Map<String, List<Long>> deviceIds, String gateway) {
        Map<String, Object> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, resolve(v, spaceId, deviceIds, gateway)));
        return out;
    }

    static Object resolve(Object v, long spaceId, Map<String, List<Long>> deviceIds, String gateway) {
        if (v instanceof String s) {
            if ("$space".equals(s)) {
                return spaceId;
            }
            if ("$gateway".equals(s)) {
                return gateway;
            }
            if (s.startsWith("$device:")) {
                String[] parts = s.substring(8).split("#");
                List<Long> ids = deviceIds.getOrDefault(parts[0], List.of());
                int n = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                if (n < 1 || n > ids.size()) {
                    throw new IllegalArgumentException("프리셋 기기 묶음이 없습니다: " + s);
                }
                // 목록(targetIds)에 들어가는 값은 문자열 ID
                return ids.get(n - 1);
            }
            return s;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                Object r = resolve(o, spaceId, deviceIds, gateway);
                out.add(r instanceof Long l ? Long.toString(l) : r);
            }
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, x) -> out.put(String.valueOf(k), resolve(x, spaceId, deviceIds, gateway)));
            return out;
        }
        return v;
    }
}
