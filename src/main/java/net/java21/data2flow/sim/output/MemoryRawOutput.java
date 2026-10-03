package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.RawEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 메모리 출력(시험·{@code data2flow.sim.output.mode=MEMORY}). 받은 봉투를 모아 둔다 */
public class MemoryRawOutput implements RawOutput {

    private final List<RawEnvelope> envelopes = new ArrayList<>();
    private volatile boolean failing;

    @Override
    public synchronized CompletionStage<Void> publish(RawEnvelope envelope) {
        if (failing) {
            return CompletableFuture.failedFuture(new IllegalStateException("메모리 출력 실패(시험)"));
        }
        envelopes.add(envelope);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean ready() {
        return !failing;
    }

    public synchronized List<RawEnvelope> envelopes() {
        return List.copyOf(envelopes);
    }

    public synchronized void clear() {
        envelopes.clear();
    }

    /** 시험: 발행 실패를 흉내 낸다 */
    public void failing(boolean value) {
        this.failing = value;
    }
}
