package net.java21.data2flow.sim.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.message.event.EventPayload;

import java.time.Instant;

/**
 * TODO contracts: EVT-SIM-01 실행 상태 변경 페이로드({@code sim.run.*}). 소비: core-api(SSE·홈), analytics(정답 라벨 대상 실행).
 *
 * @param status                CREATED·RUNNING·PAUSED·EVALUATING·COMPLETED·STOPPED·FAILED
 * @param simClock              현재 시뮬레이션 시각
 * @param accelerationEffective 속도 제한 뒤 가속
 * @param partial               정지된 실행을 끝난 구간까지만 판정했으면 true
 * @param at                    실제 시각
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimRunChanged(long organizationId, long runId, Long scenarioId, String status, Instant simClock,
                            int accelerationEffective, Boolean partial, String failureReason, Instant at) implements EventPayload {
}
