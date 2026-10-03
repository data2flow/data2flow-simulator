package net.java21.data2flow.sim.scenario.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시나리오 타임라인 이벤트(SIM-04.01, Scenario {@code events[]}).
 *
 * <table>
 *   <tr><td>OCCUPANCY</td><td>target {@code {spaceId}}, params {@code {count, activity?}} — at부터 인원, until이면 0으로</td></tr>
 *   <tr><td>OPENING</td><td>target {@code {spaceId} 또는 {deviceId}}, params {@code {opening: DOOR|WINDOW, open}} — until이면 다시 닫힘</td></tr>
 *   <tr><td>ACTUATOR</td><td>target {@code {deviceId}}, params {@code {capability, command, args}} — 장비 수동 조작</td></tr>
 *   <tr><td>FAULT</td><td>target {@code {targetType, targetIds}}, params {@code {kind, params, durationSec?}} — until 또는 durationSec까지</td></tr>
 * </table>
 *
 * @param id     이벤트 ID(시나리오 안에서 고유)
 * @param track  OCCUPANCY, OPENING, ACTUATOR, FAULT
 * @param at     시작(시뮬레이션 시각)
 * @param until  끝. 없으면 null
 * @param target 대상
 * @param params 값
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioEvent(String id, String track, Instant at, Instant until, Map<String, Object> target,
                            Map<String, Object> params) {

    public static final String OCCUPANCY = "OCCUPANCY";
    public static final String OPENING = "OPENING";
    public static final String ACTUATOR = "ACTUATOR";
    public static final String FAULT = "FAULT";

    public ScenarioEvent {
        target = target == null ? Map.of() : new TreeMap<>(target);
        params = params == null ? Map.of() : new TreeMap<>(params);
    }

    public Long targetLong(String key) {
        Object v = target.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public double number(String key, double fallback) {
        Object v = params.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
