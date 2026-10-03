package net.java21.data2flow.sim.run.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.common.SimErrorCode;

/**
 * 실행 상태 전이(SIM-04.02, TC-SIM-043).
 * <pre>
 * CREATED ──시작──▶ RUNNING ⇄ PAUSED
 * RUNNING ──시나리오 끝──▶ EVALUATING ──▶ COMPLETED
 * RUNNING/PAUSED ──정지──▶ STOPPED ──판정(끝난 구간까지)──▶ COMPLETED(partial)
 * RUNNING ──내부 오류──▶ FAILED
 * (PURGED 밖) ──초기화──▶ CREATED,  COMPLETED/STOPPED/FAILED ──정리──▶ PURGED
 * </pre>
 * CREATED에서 재개(RESUME)는 초기화한 실행을 다시 시작하는 것으로 본다. 허용되지 않은 전이는 409 {@code SIM_RUN_STATE_CONFLICT}.
 */
public final class RunStateMachine {

    private RunStateMachine() {
    }

    public static RunStatus next(RunStatus from, RunAction action) {
        RunStatus to = switch (action) {
            case START -> from == RunStatus.CREATED ? RunStatus.RUNNING : null;
            case PAUSE -> from == RunStatus.RUNNING ? RunStatus.PAUSED : null;
            case RESUME -> from == RunStatus.PAUSED || from == RunStatus.CREATED ? RunStatus.RUNNING : null;
            case STOP -> from == RunStatus.RUNNING || from == RunStatus.PAUSED ? RunStatus.STOPPED : null;
            case RESET -> from != RunStatus.PURGED && from != RunStatus.EVALUATING ? RunStatus.CREATED : null;
            case FINISH -> from == RunStatus.RUNNING ? RunStatus.EVALUATING : null;
            case COMPLETE -> from == RunStatus.EVALUATING || from == RunStatus.STOPPED ? RunStatus.COMPLETED : null;
            case FAIL -> from == RunStatus.RUNNING || from == RunStatus.PAUSED || from == RunStatus.EVALUATING ? RunStatus.FAILED : null;
            case PURGE -> from == RunStatus.COMPLETED || from == RunStatus.STOPPED || from == RunStatus.FAILED ? RunStatus.PURGED : null;
        };
        if (to == null) {
            throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
        }
        return to;
    }

    public static boolean allowed(RunStatus from, RunAction action) {
        try {
            next(from, action);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
