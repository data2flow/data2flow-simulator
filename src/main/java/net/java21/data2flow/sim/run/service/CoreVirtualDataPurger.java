package net.java21.data2flow.sim.run.service;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.sim.common.SimDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * core-api에 가상 데이터 정리를 요청한다. TODO core: 내부 API {@code POST /internal/core/sim/data/purge {organizationId, runIds}}
 * (API-SIM-25의 내부판)가 아직 없다. 없으면(404 등) 실패로 보고 다음 날 다시 시도한다.
 */
public class CoreVirtualDataPurger implements VirtualDataPurger {

    private static final Logger log = LoggerFactory.getLogger(CoreVirtualDataPurger.class);

    private final RestClient core;

    public CoreVirtualDataPurger(RestClient.Builder builder, String coreUri) {
        this.core = builder.baseUrl(coreUri).build();
    }

    @Override
    public boolean requestPurge(long organizationId, List<Long> runIds) {
        try {
            core.post().uri("/internal/core/sim/data/purge").header(DataflowHeaders.CALLER_SERVICE, SimDirectory.CALLER)
                    .body(Map.of("organizationId", organizationId, "runIds", runIds.stream().map(String::valueOf).toList()))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("가상 데이터 정리 요청 실패(조직 {}, 실행 {}): {}", organizationId, runIds, e.toString());
            return false;
        }
    }
}
