package net.java21.data2flow.sim.run.domain;

import java.util.Set;

/** 실행 상태(erd {@code ck_runs_status}, SIM domain-model "상태 전이: SimRun.status") */
public enum RunStatus {
    CREATED, RUNNING, PAUSED, EVALUATING, COMPLETED, STOPPED, FAILED, PURGED;

    /** 동시 실행 한도에 세는 상태(BR-SIM-08, TC-SIM-114: PAUSED 포함) */
    public static final Set<RunStatus> ACTIVE = Set.of(RUNNING, PAUSED, EVALUATING);

    public boolean active() {
        return ACTIVE.contains(this);
    }

    public boolean terminal() {
        return this == COMPLETED || this == STOPPED || this == FAILED || this == PURGED;
    }
}
