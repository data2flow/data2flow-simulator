package net.java21.data2flow.sim.run;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.run.domain.RunAction;
import net.java21.data2flow.sim.run.domain.RunStateMachine;
import net.java21.data2flow.sim.run.domain.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SimRunStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED,START,RUNNING", "RUNNING,PAUSE,PAUSED", "PAUSED,RESUME,RUNNING", "CREATED,RESUME,RUNNING",
            "RUNNING,FINISH,EVALUATING", "EVALUATING,COMPLETE,COMPLETED", "RUNNING,STOP,STOPPED", "PAUSED,STOP,STOPPED",
            "STOPPED,COMPLETE,COMPLETED", "RUNNING,FAIL,FAILED", "PAUSED,FAIL,FAILED", "RUNNING,RESET,CREATED",
            "COMPLETED,RESET,CREATED", "FAILED,RESET,CREATED", "COMPLETED,PURGE,PURGED", "STOPPED,PURGE,PURGED", "FAILED,PURGE,PURGED"})
    @DisplayName("[SIM-04.02][AT-SIM-08.2][TC-SIM-043] 허용 전이")
    void allowed(RunStatus from, RunAction action, RunStatus to) {
        assertThat(RunStateMachine.next(from, action)).isEqualTo(to);
        assertThat(RunStateMachine.allowed(from, action)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,PAUSE", "CREATED,PAUSE", "PAUSED,PAUSE", "RUNNING,START", "COMPLETED,RESUME", "CREATED,STOP",
            "EVALUATING,RESET", "PURGED,RESET", "RUNNING,PURGE", "CREATED,FINISH", "RUNNING,COMPLETE", "COMPLETED,FAIL"})
    @DisplayName("[SIM-04.02][AT-SIM-08.2][TC-SIM-043] 허용되지 않은 전이 → SIM_RUN_STATE_CONFLICT")
    void conflict(RunStatus from, RunAction action) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> RunStateMachine.next(from, action));
        assertThat(e.getErrorCode()).isEqualTo(SimErrorCode.SIM_RUN_STATE_CONFLICT);
        assertThat(RunStateMachine.allowed(from, action)).isFalse();
        assertThat(RunStatus.PAUSED.active()).isTrue();
        assertThat(RunStatus.STOPPED.terminal()).isTrue();
    }
}
