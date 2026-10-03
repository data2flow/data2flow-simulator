package net.java21.data2flow.sim.heartbeat;

import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.sim.common.SimDirectory;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.output.RawOutput;
import net.java21.data2flow.sim.payload.EncodedUplink;
import net.java21.data2flow.sim.payload.PayloadEncoder;
import net.java21.data2flow.sim.payload.UplinkFrame;
import net.java21.data2flow.sim.random.SimRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 전 구간 하트비트 카나리(ING-07.05, NFR-02.10, reliability-and-ha.md §6). 시스템 가상 기기 {@code __heartbeat__}의 메시지를 10초마다
 * {@code data2flow.raw}에 넣는다. pipeline → flow → action이 단계별 통과 시각을 덧붙이고, action이 {@code ingest.heartbeat.passed}
 * (EVT-ING-07)를 낸다. 30초 넘게 통과하지 못하면 CRITICAL 알람(pipeline·core 몫).
 *
 * <p>payload는 SIM 소스 형식(기본 ChirpStack v4)이고 측정값 {@code heartbeat}(순번)와 최상위 {@code heartbeat:{seq, sentAt, instance}}를
 * 싣는다. 측정 시각은 실제 현재 시각이다. 배포 조직에만 보낸다(ADR-030).
 */
public class HeartbeatCanary {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatCanary.class);

    private final SimProperties properties;
    private final SimDirectory directory;
    private final RawOutput output;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();
    private final long bootMs;
    private volatile Instant lastSentAt;

    public HeartbeatCanary(SimProperties properties, SimDirectory directory, RawOutput output, Clock clock) {
        this.properties = properties;
        this.directory = directory;
        this.output = output;
        this.clock = clock;
        this.bootMs = clock.millis();
    }

    @Scheduled(fixedRateString = "${data2flow.sim.heartbeat.interval:10s}")
    public void scheduled() {
        if (properties.heartbeat().enabled()) {
            beat();
        }
    }

    /** 한 번 보낸다. 보낸 봉투(대상 조직·소스를 못 찾으면 비어 있음) */
    public Optional<RawEnvelope> beat() {
        SimProperties.Heartbeat hb = properties.heartbeat();
        Long org = hb.organizationId() != null ? hb.organizationId() : directory.organizations().stream().findFirst().orElse(null);
        if (org == null) {
            return Optional.empty();
        }
        Optional<SimDirectory.SimSource> source = hb.sourceId() != null
                ? Optional.of(new SimDirectory.SimSource(org, hb.sourceId(), null)) : directory.source(org);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        long n = seq.incrementAndGet();
        Instant now = clock.instant();
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("heartbeat", (double) n);
        Map<String, String> tags = new TreeMap<>();
        tags.put("heartbeat", "true");
        tags.put("instance", properties.instanceId());
        tags.put("sentAt", now.toString());
        UplinkFrame frame = new UplinkFrame(org, source.get().sourceId(), hb.externalId(), hb.externalId(), "heartbeat", now, values,
                Map.of(), n, SimulationWorld.defaultGateway(org), -40, 10, SimRandom.uuid(bootMs, properties.instanceId(), "heartbeat", n), tags);
        PayloadFormat format = source.get().payloadFormat() == null ? PayloadFormat.CHIRPSTACK_V4 : source.get().payloadFormat();
        List<EncodedUplink> encoded = PayloadEncoder.encode(format, frame, PayloadEncoder.bucketWidth(10));
        RawEnvelope last = null;
        for (EncodedUplink e : encoded) {
            RawEnvelope env = new RawEnvelope(RawEnvelope.VERSION, SimRandom.uuid(bootMs, properties.instanceId(), "heartbeat-message", n),
                    org, source.get().sourceId(), SourceTypes.SIMULATION, e.topic(), e.payload(), now, properties.instanceId(),
                    e.dedupKey(), true, null);
            output.publish(env).whenComplete((ok, ex) -> {
                if (ex != null) {
                    log.debug("하트비트 발행 실패: {}", ex.toString());
                } else {
                    lastSentAt = now;
                }
            });
            last = env;
        }
        return Optional.ofNullable(last);
    }

    public Instant lastSentAt() {
        return lastSentAt;
    }
}
