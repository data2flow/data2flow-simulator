package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.EventPayload;
import net.java21.data2flow.contracts.messaging.MessageHeaders;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** AMQP 발행: 발행 확인(publisher confirm)을 기다린다 */
public class AmqpSimEventPublisher implements SimEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AmqpSimEventPublisher.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final MessageCodec codec = MessageCodec.create();

    public AmqpSimEventPublisher(RabbitTemplate rabbit, Clock clock) {
        this.rabbit = rabbit;
        this.clock = clock;
    }

    @Override
    public boolean publish(EventType type, long organizationId, EventPayload payload) {
        DomainEvent<EventPayload> event = DomainEvent.of(type, organizationId, payload, null, clock);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.messageId().toString());
        MessageHeaders.of(event).forEach(props::setHeader);
        CorrelationData correlation = new CorrelationData(event.messageId().toString());
        try {
            rabbit.send(MessagingNames.EXCHANGE_EVENTS, type.routingKey(), new Message(codec.write(event), props), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                log.warn("이벤트 발행 거부: {} {}", type.routingKey(), confirm.reason());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("이벤트 발행 실패: {} ({})", type.routingKey(), e.toString());
            return false;
        }
    }
}
