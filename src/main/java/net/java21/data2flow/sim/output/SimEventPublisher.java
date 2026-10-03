package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.event.EventPayload;

/** 도메인 이벤트 발행(topic {@code data2flow.events}, 봉투 contracts {@code DomainEvent}, 라우팅 키 = type) */
public interface SimEventPublisher {

    /**
     * @param type 라우팅 키(예: {@code sim.run.started})
     * @return 발행 확인을 받았으면 true(실패는 로그만 남기고 false: ack는 action 타임아웃·재시도로 회복)
     */
    boolean publish(String type, long organizationId, EventPayload payload);
}
