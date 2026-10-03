package net.java21.data2flow.sim.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.message.event.EventPayload;

import java.time.Instant;
import java.util.Map;

/**
 * TODO contracts: 가상 장비 상태 보고(EVT-SIM-03 = EVT-ACT-07 {@code device.state.reported}). action은 버전이 이전보다 클 때만
 * 반영한다(BR-ACT-05). 모양은 ACT-api {@code {deviceId, version, capabilities:{capability:{attr:value}}, reportedAt}} + {@code virtual}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceStateReported(long deviceId, long version, Map<String, Map<String, Object>> capabilities, Instant reportedAt,
                                  boolean virtual) implements EventPayload {
}
