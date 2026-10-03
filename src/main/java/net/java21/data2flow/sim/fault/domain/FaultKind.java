package net.java21.data2flow.sim.fault.domain;

/**
 * 장애 종류(SIM-05.01 센서 7종, SIM-05.02 인프라 5종, erd {@code ck_faults_kind}).
 */
public enum FaultKind {
    STUCK(true), SPIKE(true), DRIFT(true), DROPOUT(true), INTERMITTENT(true), BATTERY_DRAIN(true), OUT_OF_RANGE(true),
    GATEWAY_DOWN(false), DUPLICATE(false), REORDER(false), DELAY(false), MALFORMED(false);

    private final boolean sensor;

    FaultKind(boolean sensor) {
        this.sensor = sensor;
    }

    /** 측정 값을 바꾸는 센서 장애인가(아니면 전송을 바꾸는 인프라 장애) */
    public boolean sensor() {
        return sensor;
    }
}
