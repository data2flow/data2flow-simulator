package net.java21.data2flow.sim.scenario.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;

import java.time.Instant;
import java.util.List;

/**
 * 시나리오(SIM-04.01, erd {@code scenarios}).
 *
 * @param name         이름(조직 안에서 고유)
 * @param spaceIds     대상 가상 공간
 * @param simStartAt   시작 시뮬레이션 시각
 * @param durationSec  길이 3,600~604,800초
 * @param seed         시드. 없으면 실행마다 무작위
 * @param useCalendar  조직 달력 반영(SIM-10.02, M7)
 * @param outdoor      외기
 * @param events       이벤트(최대 2,000개)
 * @param expectations 기대 결과
 * @param presetKey    데모 프리셋에서 만들었으면 키
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Scenario(String name, List<Long> spaceIds, Instant simStartAt, int durationSec, Long seed, boolean useCalendar,
                       OutdoorSpec outdoor, List<ScenarioEvent> events, List<Expectation> expectations, String presetKey) {

    public static final int MIN_DURATION_SEC = 3_600;
    public static final int MAX_DURATION_SEC = 604_800;
    public static final int MAX_EVENTS = 2_000;

    public Scenario {
        spaceIds = spaceIds == null ? List.of() : List.copyOf(spaceIds);
        events = events == null ? List.of() : List.copyOf(events);
        expectations = expectations == null ? List.of() : List.copyOf(expectations);
        outdoor = outdoor == null ? new OutdoorSpec(null, null, null, null) : outdoor;
    }

    public Instant simEndAt() {
        return simStartAt.plusSeconds(durationSec);
    }

    public Scenario withName(String newName) {
        return new Scenario(newName, spaceIds, simStartAt, durationSec, seed, useCalendar, outdoor, events, expectations, presetKey);
    }
}
