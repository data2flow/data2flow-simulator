package net.java21.data2flow.sim.scenario.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * 기대 결과(SIM-04.06, M4). 시뮬레이터가 스스로 판정할 수 있는 DEVICE_STATE_REACHED·METRIC_RANGE_RATIO·CONTROL_COUNT_MAX는
 * 판정하고, 알람이 필요한 ALARM_COUNT는 RUL(M4)이 생기기 전까지 SKIPPED로 둔다.
 *
 * @param id        ID
 * @param kind      DEVICE_STATE_REACHED, ALARM_COUNT, METRIC_RANGE_RATIO, CONTROL_COUNT_MAX
 * @param target    대상
 * @param condition 조건
 * @param deadline  기한(시뮬레이션 시각). 없으면 실행 끝
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Expectation(String id, String kind, Map<String, Object> target, Map<String, Object> condition, Instant deadline) {

    public Expectation {
        target = target == null ? Map.of() : new TreeMap<>(target);
        condition = condition == null ? Map.of() : new TreeMap<>(condition);
    }
}
