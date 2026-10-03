package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * 시뮬레이션 하나(시나리오 실행 또는 상시 환경)의 고정 설정.
 *
 * @param runId          실행 ID. 상시 환경이면 null
 * @param organizationId 조직
 * @param seed           실행 시드(BR-SIM-07)
 * @param simStart       시작 시뮬레이션 시각
 * @param tickSec        틱 길이(기본 10초)
 * @param totalTicks     끝까지 틱 수. 상시 환경이면 0(끝 없음)
 * @param timestampPolicy 측정 시각 정책
 * @param zone           조직 시간대(일주기·시간표)
 * @param outdoor        외기
 * @param spaces         공간
 * @param devices        기기
 * @param events         시나리오 이벤트(FAULT 트랙은 {@code faults}로 풀어서 넣음)
 * @param faults         예약·진행 중 장애
 * @param expectations   기대 결과(SIM-04.06)
 */
public record WorldConfig(Long runId, long organizationId, long seed, Instant simStart, int tickSec, long totalTicks,
                          TimestampPolicy timestampPolicy, ZoneId zone, OutdoorSpec outdoor, List<WorldSpace> spaces,
                          List<WorldDevice> devices, List<ScenarioEvent> events, List<FaultSpec> faults,
                          List<Expectation> expectations) {

    public static final int DEFAULT_TICK_SEC = 10;

    public WorldConfig {
        spaces = List.copyOf(spaces);
        devices = List.copyOf(devices);
        events = events == null ? List.of() : List.copyOf(events);
        faults = faults == null ? List.of() : List.copyOf(faults);
        expectations = expectations == null ? List.of() : List.copyOf(expectations);
        timestampPolicy = timestampPolicy == null ? TimestampPolicy.SIMULATED : timestampPolicy;
        zone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
        outdoor = outdoor == null ? new OutdoorSpec(null, null, null, null) : outdoor;
        if (tickSec <= 0) {
            throw new IllegalArgumentException("tickSec > 0");
        }
    }

    public Instant simEnd() {
        return totalTicks <= 0 ? null : simStart.plusSeconds(totalTicks * tickSec);
    }
}
