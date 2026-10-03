package net.java21.data2flow.sim.support;

import io.micrometer.core.instrument.MeterRegistry;
import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.contracts.messaging.SuperStreamSpec;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.output.RawStreamPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 시험: 실제 data2flow.raw에 발행하면서 보낸 봉투를 모은다. 실패 흉내도 낸다 */
public class RecordingRawOutput extends RawStreamPublisher {

    private final List<RawEnvelope> sent = new ArrayList<>();
    private volatile boolean failing;

    public RecordingRawOutput(SimProperties.Stream config, MeterRegistry registry) {
        super(config, SuperStreamSpec.RAW, registry);
    }

    @Override
    public CompletionStage<Void> publish(RawEnvelope envelope) {
        if (failing) {
            return CompletableFuture.failedFuture(new IllegalStateException("시험: 발행 실패"));
        }
        return super.publish(envelope).thenRun(() -> {
            synchronized (sent) {
                sent.add(envelope);
            }
        });
    }

    public List<RawEnvelope> sent() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    public void clear() {
        synchronized (sent) {
            sent.clear();
        }
    }

    public void failing(boolean value) {
        failing = value;
    }
}
