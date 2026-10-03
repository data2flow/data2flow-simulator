package net.java21.data2flow.sim.contracts;

/** TODO contracts: {@code data2flow.events} 라우팅 키(= DomainEvent.type) */
public final class SimEventTypes {

    /** EVT-SIM-01 실행 상태: {@code sim.run.{started|paused|resumed|stopped|completed|failed|throttled}} */
    public static final String RUN_PREFIX = "sim.run.";
    /** EVT-SIM-02 장애 정답 라벨 */
    public static final String FAULT_STARTED = "sim.fault.started";
    public static final String FAULT_ENDED = "sim.fault.ended";
    /** EVT-SIM-03 = EVT-ACT-06 */
    public static final String COMMAND_ACK = "device.command.ack";
    /** EVT-SIM-03 = EVT-ACT-07 */
    public static final String STATE_REPORTED = "device.state.reported";

    private SimEventTypes() {
    }

    public static String run(String suffix) {
        return RUN_PREFIX + suffix;
    }
}
