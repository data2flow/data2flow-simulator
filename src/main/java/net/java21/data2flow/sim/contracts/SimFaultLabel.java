package net.java21.data2flow.sim.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.message.event.EventPayload;

import java.time.Instant;
import java.util.Map;

/**
 * TODO contracts: EVT-SIM-02 장애 정답 라벨({@code sim.fault.started|ended}). 소비: analytics(이상 탐지 평가, SIM-05.04), core-api(실행 로그).
 * 시각은 모두 시뮬레이션 시각이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimFaultLabel(long organizationId, Long runId, long faultId, String kind, String targetType, String targetId,
                            Instant simFrom, Instant simTo, Map<String, Object> params) implements EventPayload {
}
