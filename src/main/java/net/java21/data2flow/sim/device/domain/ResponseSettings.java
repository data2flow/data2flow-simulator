package net.java21.data2flow.sim.device.domain;

/**
 * 가상 장비 응답 특성(SIM-03.04, {@code devices.response}).
 *
 * @param reactionDelaySec 명령이 물리 모델에 반영되기까지(시뮬레이션 초). null이면 유형 특성 {@code reactionDelaySec}
 * @param ackDelayMs       응답(ack)까지 지연(시뮬레이션 ms)
 * @param failurePct       응답 실패 확률 %(이 확률로 ack를 보내지 않고 적용도 하지 않음, BR-SIM-05)
 */
public record ResponseSettings(Integer reactionDelaySec, Integer ackDelayMs, Double failurePct) {

    public static final ResponseSettings DEFAULT = new ResponseSettings(null, 200, 0.0);

    public int ackDelay() {
        return ackDelayMs == null ? 200 : ackDelayMs;
    }

    public double failure() {
        return failurePct == null ? 0 : failurePct;
    }
}
