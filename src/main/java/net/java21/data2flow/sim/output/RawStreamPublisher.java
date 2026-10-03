package net.java21.data2flow.sim.output;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.ByteCapacity;
import com.rabbitmq.stream.Constants;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.EnvironmentBuilder;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.MessageBuilder;
import com.rabbitmq.stream.Producer;
import com.rabbitmq.stream.StreamException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.contracts.messaging.MessageHeaders;
import net.java21.data2flow.contracts.messaging.SuperStreamSpec;
import net.java21.data2flow.sim.common.SimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code data2flow.raw} 직접 주입(SIM-02.07 INTERNAL, BR-SIM-01, EVT-ING-01). ingress {@code RawStreamWriter}와 같은 방식이다:
 * 라우팅 키 {@code RawEnvelope.routingKey()}(sha1(sourceId + topic)), 헤더 {@code MessageHeaders.of}, publisher confirm을 받으면 완료.
 * 생산자 내부 재전송은 끄고(실패하면 실행기가 체크포인트에서 같은 메시지를 다시 만든다), 브로커가 없으면 백오프로 다시 연결한다.
 */
public class RawStreamPublisher implements RawOutput, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RawStreamPublisher.class);
    public static final String ROUTING_KEY_HEADER = "routingKey";

    private final SimProperties.Stream config;
    private final SuperStreamSpec spec;
    private final MessageCodec codec = MessageCodec.create();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("sim-raw-stream").factory());
    private final Counter confirmed;
    private final Counter failed;
    private volatile Environment environment;
    private volatile Producer producer;
    private volatile boolean running;
    private volatile String lastError;
    private long backoffMs = 1000;

    public RawStreamPublisher(SimProperties.Stream config, SuperStreamSpec spec, MeterRegistry registry) {
        this.config = config;
        this.spec = spec;
        this.confirmed = Counter.builder("data2flow.sim.raw.published").tag("result", "confirmed")
                .description("가상 원본 data2flow.raw 기록 결과").register(registry);
        this.failed = Counter.builder("data2flow.sim.raw.published").tag("result", "failed")
                .description("가상 원본 data2flow.raw 기록 결과").register(registry);
    }

    @Override
    public CompletionStage<Void> publish(RawEnvelope envelope) {
        Producer p = producer;
        if (p == null) {
            failed.increment();
            return CompletableFuture.failedFuture(new IllegalStateException("data2flow.raw 생산자가 준비되지 않았습니다"
                    + (lastError == null ? "" : ": " + lastError)));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            MessageBuilder mb = p.messageBuilder();
            mb.properties().messageId(envelope.messageId().toString()).contentType("application/json");
            MessageBuilder.ApplicationPropertiesBuilder props = mb.applicationProperties();
            MessageHeaders.of(envelope).forEach((k, v) -> props.entry(k, String.valueOf(v)));
            props.entry(ROUTING_KEY_HEADER, envelope.routingKey());
            Message message = props.messageBuilder().addData(codec.write(envelope)).build();
            p.send(message, status -> {
                if (status.isConfirmed()) {
                    confirmed.increment();
                    result.complete(null);
                } else {
                    failed.increment();
                    result.completeExceptionally(new IllegalStateException("data2flow.raw confirm 실패(code " + status.getCode() + ")"));
                }
            });
        } catch (RuntimeException e) {
            failed.increment();
            result.completeExceptionally(e);
        }
        return result;
    }

    @Override
    public boolean ready() {
        return producer != null;
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public void start() {
        running = true;
        scheduler.execute(this::initialize);
    }

    private void initialize() {
        if (!running || producer != null) {
            return;
        }
        try {
            EnvironmentBuilder b = Environment.builder().host(config.host()).port(config.port()).virtualHost(config.virtualHost())
                    .username(config.username()).password(config.password());
            if (config.useConfiguredAddress()) {
                Address fixed = new Address(config.host(), config.port());
                b.addressResolver(address -> fixed);
            }
            Environment env = b.build();
            if (config.createSuperStream()) {
                try {
                    env.streamCreator().name(spec.name()).superStream().partitions(spec.partitions()).creator()
                            .maxAge(spec.maxAge()).maxLengthBytes(ByteCapacity.B(spec.maxBytesPerPartition())).create();
                } catch (StreamException e) {
                    if (e.getCode() != Constants.RESPONSE_CODE_STREAM_ALREADY_EXISTS) {
                        throw e;
                    }
                }
            }
            Producer p = env.producerBuilder().superStream(spec.name())
                    .routing(m -> String.valueOf(m.getApplicationProperties().get(ROUTING_KEY_HEADER))).producerBuilder()
                    .confirmTimeout(config.confirmTimeout()).maxUnconfirmedMessages(config.maxUnconfirmed())
                    .retryOnRecovery(false).build();
            environment = env;
            producer = p;
            lastError = null;
            backoffMs = 1000;
            log.info("data2flow.raw 생산자 준비됨({}:{} vhost {})", config.host(), config.port(), config.virtualHost());
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("data2flow.raw 생산자를 만들지 못했습니다({}), {}ms 뒤 다시 시도", lastError, backoffMs);
            closeQuietly();
            if (running) {
                scheduler.schedule(this::initialize, backoffMs, TimeUnit.MILLISECONDS);
                backoffMs = Math.min(30_000, backoffMs * 2);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly();
        scheduler.shutdownNow();
    }

    private void closeQuietly() {
        Producer p = producer;
        producer = null;
        Environment env = environment;
        environment = null;
        try {
            if (p != null) {
                p.close();
            }
            if (env != null) {
                env.close();
            }
        } catch (RuntimeException e) {
            log.debug("스트림 닫기 실패: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 실행기(executor)보다 늦게 멈춘다: 실행기가 먼저 체크포인트를 남기고 멈춘 뒤 생산자를 닫는다 */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 200;
    }
}
