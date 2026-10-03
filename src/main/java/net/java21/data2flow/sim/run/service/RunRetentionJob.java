package net.java21.data2flow.sim.run.service;

import net.java21.data2flow.sim.common.SimDirectory;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.run.domain.RunAction;
import net.java21.data2flow.sim.run.domain.RunStateMachine;
import net.java21.data2flow.sim.run.domain.RunStatus;
import net.java21.data2flow.sim.run.repository.LeaseRepository;
import net.java21.data2flow.sim.run.repository.RunRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 보관 기한 정리(SIM-11.03, BR-SIM-14): 끝난 지 30일(연장했으면 그 기한)이 지난 실행의 가상 데이터 정리를 core에 요청하고 PURGED로 표시한다.
 * 다른 실행은 건드리지 않는다. 인스턴스가 여럿이면 하나만 돈다(leases).
 */
@Component
public class RunRetentionJob {

    private final RunRepository runs;
    private final LeaseRepository leases;
    private final VirtualDataPurger purger;
    private final SimDirectory directory;
    private final SimProperties properties;
    private final Clock clock;

    public RunRetentionJob(RunRepository runs, LeaseRepository leases, VirtualDataPurger purger, SimDirectory directory,
                           SimProperties properties, Clock clock) {
        this.runs = runs;
        this.leases = leases;
        this.purger = purger;
        this.directory = directory;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${data2flow.sim.retention.cron:0 17 3 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (properties.retention().enabled()) {
            purgeExpired();
        }
    }

    /** @return PURGED로 표시한 실행 ID */
    public List<Long> purgeExpired() {
        Instant now = clock.instant();
        if (!leases.acquire("retention", properties.instanceId(), now, now.plusSeconds(600))) {
            return List.of();
        }
        Map<Long, List<RunRepository.RunRow>> byOrg = new TreeMap<>();
        for (RunRepository.RunRow r : runs.listExpired(now)) {
            if (directory.allowed(r.organizationId())) {
                byOrg.computeIfAbsent(r.organizationId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Long> purged = new ArrayList<>();
        byOrg.forEach((org, list) -> {
            if (!purger.requestPurge(org, list.stream().map(RunRepository.RunRow::id).toList())) {
                return;
            }
            for (RunRepository.RunRow r : list) {
                RunStatus to = RunStateMachine.next(r.status(), RunAction.PURGE);
                if (runs.transition(org, r.id(), r.status(), to, now) > 0) {
                    purged.add(r.id());
                }
            }
        });
        leases.release("retention", properties.instanceId());
        return purged;
    }
}
