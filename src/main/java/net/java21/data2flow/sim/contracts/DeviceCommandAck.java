package net.java21.data2flow.sim.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.message.event.EventPayload;

import java.time.Instant;

/**
 * TODO contracts: 가상 장비 명령 응답(EVT-SIM-03 = EVT-ACT-06 {@code device.command.ack}). action이 명령을 SENT → ACKED/FAILED로 바꾼다.
 *
 * @param result  ACKED 또는 FAILED(ACT-api 기준. SIM-api의 REJECTED는 FAILED + reason으로 낸다)
 * @param reason  FAILED 이유(INVALID_COMMAND, DEVICE_NOT_SIMULATED)
 * @param at      응답 시각(시뮬레이션 시각, SIMULATED 정책)
 * @param virtual 가상 장비 응답(SIM-07.01)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceCommandAck(String commandId, long deviceId, String result, String reason, Instant at, boolean virtual)
        implements EventPayload {
}
