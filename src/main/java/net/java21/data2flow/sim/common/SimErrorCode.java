package net.java21.data2flow.sim.common;

import net.java21.data2flow.contracts.error.ErrorCode;

/** SIM 도메인 오류 코드(spec/detail/SIM/domain-model.md "오류 코드"). 문구는 messages*.properties의 error.<코드> */
public enum SimErrorCode implements ErrorCode {
    SIM_DEVICE_QUOTA_EXCEEDED(409),
    SIM_CONCURRENT_RUN_LIMIT(409),
    SIM_ACCELERATION_LIMIT(409),
    SIM_SPACE_BUSY(409),
    SIM_RUN_STATE_CONFLICT(409),
    SIM_TARGET_NOT_VIRTUAL(400),
    SIM_PROFILE_IN_USE(409),
    SIM_TYPE_IN_USE(409),
    SIM_PROPERTY_OUT_OF_RANGE(400),
    SIM_PLATFORM_BROKER_UNAVAILABLE(409),
    SIM_WEATHER_DATA_MISSING(409),
    SIM_IMPORT_INVALID(400),
    SIM_SCENARIO_INVALID(400),
    SIM_SANDBOX_VIOLATION(403),
    SIM_NOT_FOUND(404);

    private final int httpStatus;

    SimErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
