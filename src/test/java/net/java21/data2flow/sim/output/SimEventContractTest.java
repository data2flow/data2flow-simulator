package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.MessageSchemas;
import net.java21.data2flow.contracts.message.event.DeviceCommandAck;
import net.java21.data2flow.contracts.message.event.DeviceStateReported;
import net.java21.data2flow.contracts.message.event.SimFaultLabel;
import net.java21.data2flow.contracts.message.event.SimRunChanged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SIM-03.04·SIM-04.02·SIM-05.04: 시뮬레이터 이벤트는 contracts EventType·페이로드로 내고 domain-event.v1.json을 통과한다 */
class SimEventContractTest {

    private static final Instant T = Instant.parse("2026-07-15T05:00:10Z");
    private final MessageCodec codec = MessageCodec.create();
    private final MemorySimEventPublisher publisher = new MemorySimEventPublisher();

    @Test
    @DisplayName("[SIM-03.04][TC-SIM-036] ack·reported·실행·장애 이벤트가 contracts 스키마를 통과하고 라우팅 키가 그대로다")
    void eventsMatchContracts() {
        publisher.publish(EventType.DEVICE_COMMAND_ACK, 1, DeviceCommandAck.acked("c-1", 15, T, true));
        publisher.publish(EventType.DEVICE_STATE_REPORTED, 1,
                new DeviceStateReported(15, 8, Map.of("Thermostat", Map.of("mode", "cool")), T, true));
        publisher.publish(EventType.simRun("reset"), 1, new SimRunChanged(1, 42, 3L, "CREATED", T, 60, null, null, T));
        publisher.publish(EventType.SIM_FAULT_STARTED, 1,
                new SimFaultLabel(1, 42L, 9, "STUCK", "DEVICE", "21", T, null, Map.of("value", 27.5)));
        assertThat(publisher.events()).extracting(MemorySimEventPublisher.Published::type)
                .containsExactly("device.command.ack", "device.state.reported", "sim.run.reset", "sim.fault.started");
        for (MemorySimEventPublisher.Published p : publisher.events()) {
            EventType type = EventType.fromRoutingKey(p.type()).orElseThrow();
            DomainEvent<?> event = DomainEvent.of(type, p.organizationId(), p.payload(), null, Clock.fixed(T, ZoneOffset.UTC));
            MessageSchemas.assertValid(event);
            assertThat(codec.readEvent(codec.write(event)).payload()).isEqualTo(p.payload());
        }
        assertThat(codec.toTree(DomainEvent.of(EventType.DEVICE_COMMAND_ACK, 1, publisher.events().get(0).payload(), null,
                Clock.fixed(T, ZoneOffset.UTC))).get("payload").toString())
                .isEqualTo("{\"commandId\":\"c-1\",\"deviceId\":15,\"result\":\"ACKED\",\"at\":\"2026-07-15T05:00:10Z\",\"virtual\":true}");
        publisher.clear();
        assertThat(publisher.events()).isEmpty();
    }

    @Test
    @DisplayName("[SIM-03.04] 종류와 페이로드 타입이 맞지 않으면 발행하지 않는다")
    void rejectsMismatchedPayload() {
        assertThatThrownBy(() -> publisher.publish(EventType.DEVICE_COMMAND_ACK, 1,
                new SimRunChanged(1, 42, null, "RUNNING", T, 1, null, null, T))).isInstanceOf(IllegalArgumentException.class);
        assertThat(publisher.events()).isEmpty();
    }
}
