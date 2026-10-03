package net.java21.data2flow.sim.common;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 이 배포가 맡는 조직과 조직별 SIM 데이터 소스(ADR-030). staging·prod는 DB 하나를 함께 쓰므로, 상시 환경·실행 넘겨받기·하트비트는 배포 조직만
 * 돌린다. 설정({@code data2flow.sim.organization-ids}, {@code source-ids})이 있으면 그것을, 없으면 core-api 소스 실행 설정
 * (API-DSC-50 {@code GET /internal/core/sources/runtime-config}, 배포 조직으로 좁혀 줌)의 SIMULATION 소스를 쓴다.
 * core에 닿지 않으면 마지막으로 받은 값을 유지하고, 한 번도 못 받았으면 아무 조직도 돌리지 않는다(닫힌 쪽으로 실패).
 */
public class SimDirectory {

    private static final Logger log = LoggerFactory.getLogger(SimDirectory.class);
    public static final String CALLER = "data2flow-simulator";

    /**
     * @param sourceId      SIM 소스 ID
     * @param payloadFormat 소스 디코더에 맞는 payload 형식(모르면 null)
     */
    public record SimSource(long organizationId, long sourceId, PayloadFormat payloadFormat) {
    }

    private final SimProperties properties;
    private final RestClient core;
    private final Clock clock;
    private volatile Map<Long, SimSource> fromCore = Map.of();
    private volatile Instant refreshedAt = Instant.EPOCH;

    public SimDirectory(SimProperties properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.core = builder.baseUrl(properties.coreUri()).build();
        this.clock = clock;
    }

    /** 이 배포가 시뮬레이션할 조직 */
    public List<Long> organizations() {
        TreeSet<Long> ids = new TreeSet<>(properties.organizationIds());
        if (ids.isEmpty()) {
            refreshIfStale();
            ids.addAll(fromCore.keySet());
        }
        return new ArrayList<>(ids);
    }

    public boolean allowed(long organizationId) {
        return organizations().contains(organizationId);
    }

    /** 조직의 SIM 소스 */
    public Optional<SimSource> source(long organizationId) {
        Long configured = properties.sourceIds().get(organizationId);
        if (configured != null) {
            return Optional.of(new SimSource(organizationId, configured, null));
        }
        refreshIfStale();
        return Optional.ofNullable(fromCore.get(organizationId));
    }

    private void refreshIfStale() {
        Instant now = clock.instant();
        if (now.isBefore(refreshedAt.plus(properties.directoryRefresh()))) {
            return;
        }
        refreshedAt = now;
        try {
            JsonNode body = core.get().uri("/internal/core/sources/runtime-config")
                    .header(DataflowHeaders.CALLER_SERVICE, CALLER).retrieve().body(JsonNode.class);
            Map<Long, SimSource> map = new TreeMap<>();
            JsonNode sources = body == null ? null : body.path("response").path("sources");
            if (sources != null && sources.isArray()) {
                for (JsonNode s : sources) {
                    if (!SourceTypes.SIMULATION.equals(s.path("type").asString(""))) {
                        continue;
                    }
                    long org = Long.parseLong(s.path("organizationId").asString("0"));
                    long id = Long.parseLong(s.path("id").asString("0"));
                    if (org > 0 && id > 0) {
                        map.putIfAbsent(org, new SimSource(org, id, PayloadFormat.ofDecoderKey(s.path("decoderKey").asString(null))));
                    }
                }
            }
            fromCore = Map.copyOf(map);
        } catch (RuntimeException e) {
            log.warn("core 배포 조직·SIM 소스를 읽지 못했습니다({}). 마지막 값 {}개 조직을 유지합니다", e.toString(), fromCore.size());
        }
    }
}
