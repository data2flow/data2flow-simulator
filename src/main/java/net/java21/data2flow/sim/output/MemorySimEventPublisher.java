package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.event.EventPayload;

import java.util.ArrayList;
import java.util.List;

/** 메모리 이벤트(시험·이벤트 끔). 발행한 것을 모아 둔다 */
public class MemorySimEventPublisher implements SimEventPublisher {

    public record Published(String type, long organizationId, EventPayload payload) {
    }

    private final List<Published> events = new ArrayList<>();

    @Override
    public synchronized boolean publish(String type, long organizationId, EventPayload payload) {
        events.add(new Published(type, organizationId, payload));
        return true;
    }

    public synchronized List<Published> events() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}
