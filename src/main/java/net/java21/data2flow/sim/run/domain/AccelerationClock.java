package net.java21.data2flow.sim.run.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;

import java.time.Instant;
import java.util.List;

/**
 * 시간 가속(SIM-04.03, TC-SIM-048): 실제 1초 = 시뮬레이션 {@code acceleration}초. 기준점(실제 시각, 틱)에서 지난 실제 시간 × 가속으로 목표
 * 틱을 정한다. 가속을 바꾸거나 일시정지했다 재개하면 기준점을 지금으로 옮겨 시뮬레이션 시각이 끊기지 않는다.
 *
 * @param anchorReal 기준 실제 시각
 * @param anchorTick 기준 틱
 * @param acceleration 1~60
 * @param tickSec    틱 길이(시뮬레이션 초)
 */
public record AccelerationClock(Instant anchorReal, long anchorTick, int acceleration, int tickSec) {

    public static final int MIN = 1;
    public static final int MAX = 60;

    public AccelerationClock {
        validate(acceleration);
    }

    public static void validate(int acceleration) {
        if (acceleration < MIN || acceleration > MAX) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("acceleration", "Range", "가속은 " + MIN + "~" + MAX + "입니다")));
        }
    }

    /** 지금(실제)까지 끝나 있어야 할 틱 */
    public long targetTick(Instant realNow) {
        long elapsedMs = Math.max(0, realNow.toEpochMilli() - anchorReal.toEpochMilli());
        return anchorTick + (elapsedMs * acceleration) / (tickSec * 1000L);
    }

    /** 가속을 바꾼다: 지금 진행한 틱을 새 기준점으로(연속) */
    public AccelerationClock withAcceleration(int newAcceleration, Instant realNow, long currentTick) {
        return new AccelerationClock(realNow, currentTick, newAcceleration, tickSec);
    }

    /** 일시정지 뒤 재개: 기준점을 지금으로 */
    public AccelerationClock rebase(Instant realNow, long currentTick) {
        return new AccelerationClock(realNow, currentTick, acceleration, tickSec);
    }

    /** 끝까지 걸리는 실제 시간(초) */
    public static double realSeconds(long totalSimSec, int acceleration) {
        return totalSimSec / (double) acceleration;
    }
}
