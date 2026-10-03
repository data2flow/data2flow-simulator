package net.java21.data2flow.sim.run.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.common.SimErrorCode;

import java.util.List;

/**
 * 실행 한도(SIM-11.01, BR-SIM-08): x60에서 기기 100대 → 기기 수 × 가속 ≤ 100 × 60. 넘으면 {@code SIM_ACCELERATION_LIMIT}과 함께
 * 허용 가속을 알려 준다(예: 기기 101대 → x59, x30은 허용).
 */
public final class RunLimits {

    public static final String ALLOWED_HEADER = "X-SIM-ACCELERATION-ALLOWED";

    private RunLimits() {
    }

    /** 이 기기 수로 쓸 수 있는 최대 가속 */
    public static int allowedAcceleration(int deviceCount, int devicesAtMaxAcceleration) {
        long budget = (long) devicesAtMaxAcceleration * AccelerationClock.MAX;
        return (int) Math.max(1, Math.min(AccelerationClock.MAX, budget / Math.max(1, deviceCount)));
    }

    public static void checkAcceleration(int deviceCount, int acceleration, int devicesAtMaxAcceleration) {
        long budget = (long) devicesAtMaxAcceleration * AccelerationClock.MAX;
        if ((long) deviceCount * acceleration > budget) {
            int allowed = allowedAcceleration(deviceCount, devicesAtMaxAcceleration);
            throw new BusinessException(SimErrorCode.SIM_ACCELERATION_LIMIT, List.of(new FieldErrorDetail("acceleration",
                    SimErrorCode.SIM_ACCELERATION_LIMIT.code(), "기기 " + deviceCount + "대는 x" + allowed + " 이하로 실행할 수 있습니다")),
                    allowed).withHeader(ALLOWED_HEADER, Integer.toString(allowed));
        }
    }
}
