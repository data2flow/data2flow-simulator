package net.java21.data2flow.sim.fault.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * 주입한 장애 하나(SIM-05, erd {@code faults}). 시각은 모두 시뮬레이션 시각이고, 이 값이 그대로 정답 라벨이 된다(SIM-05.04).
 *
 * @param faultId    장애 ID({@code faults.id})
 * @param kind       종류
 * @param targetType DEVICE, GATEWAY
 * @param targetId   기기 ID(문자열) 또는 가상 게이트웨이 EUI
 * @param params     강도(API-SIM-20 표)
 * @param simFrom    시작
 * @param simTo      끝(해제하면 해제 시각)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FaultSpec(long faultId, FaultKind kind, String targetType, String targetId, Map<String, Object> params,
                        Instant simFrom, Instant simTo) {

    public static final String DEVICE = "DEVICE";
    public static final String GATEWAY = "GATEWAY";

    public FaultSpec {
        params = params == null ? Map.of() : new TreeMap<>(params);
    }

    public boolean activeAt(Instant t) {
        return !t.isBefore(simFrom) && (simTo == null || t.isBefore(simTo));
    }

    public double number(String key, double fallback) {
        Object v = params.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public FaultSpec endingAt(Instant end) {
        return new FaultSpec(faultId, kind, targetType, targetId, params, simFrom, end);
    }
}
