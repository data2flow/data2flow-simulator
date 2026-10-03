package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EventPayload;

import java.util.ArrayList;
import java.util.List;

/** 메모리 이벤트(시험·이벤트 끔). 발행한 것을 모아 둔다 */
public class MemorySimEventPublisher implements SimEventPublisher {

    /** @param type 라우팅 키(예: {@code sim.run.started}) */
    public record Published(String type, long organizationId, EventPayload payload) {
    }

    private final List<Published> events = new ArrayList<>();

    @Override
    public synchronized boolean publish(EventType type, long organizationId, EventPayload payload) {
        if (!type.payloadType().isInstance(payload)) {   // AMQP 발행과 같은 검사(DomainEvent.of)
            throw new IllegalArgumentException(type + " 페이로드는 " + type.payloadType().getSimpleName() + "여야 합니다");
        }
        events.add(new Published(type.routingKey(), organizationId, payload));
        return true;
    }

    public synchronized List<Published> events() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}
