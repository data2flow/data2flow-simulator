package net.java21.data2flow.sim.run.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.contracts.SimEventTypes;
import net.java21.data2flow.sim.contracts.SimRunChanged;
import net.java21.data2flow.sim.engine.TimestampPolicy;
import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.engine.WorldSpace;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.fault.domain.FaultValidator;
import net.java21.data2flow.sim.fault.repository.FaultRepository;
import net.java21.data2flow.sim.output.SimEventPublisher;
import net.java21.data2flow.sim.run.domain.AccelerationClock;
import net.java21.data2flow.sim.run.domain.RunAction;
import net.java21.data2flow.sim.run.domain.RunLimits;
import net.java21.data2flow.sim.run.domain.RunPlan;
import net.java21.data2flow.sim.run.domain.RunStateMachine;
import net.java21.data2flow.sim.run.domain.RunStatus;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.scenario.domain.Scenario;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import net.java21.data2flow.sim.scenario.repository.ScenarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시나리오 실행 제어(SIM-04.02·04.03·04.04, SIM-11.01, API-SIM-14~17·34). 상태는 DB가 정본이고, 실행기는 주기마다 DB 상태를 따른다.
 */
@Service
public class RunService {

    public static final String KIND_SCENARIO = "SCENARIO";

    private final RunRepository runs;
    private final ScenarioRepository scenarios;
    private final FaultRepository faults;
    private final WorldFactory factory;
    private final SimulationExecutor executor;
    private final SimEventPublisher events;
    private final SimProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RunService(RunRepository runs, ScenarioRepository scenarios, FaultRepository faults, WorldFactory factory,
                      SimulationExecutor executor, SimEventPublisher events, SimProperties properties, Clock clock) {
        this.runs = runs;
        this.scenarios = scenarios;
        this.faults = faults;
        this.factory = factory;
        this.executor = executor;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * API-SIM-34 {@code POST /internal/sim/runs} 요청.
     *
     * @param acceleration       1~60(기본 1)
     * @param timestampPolicy    SIMULATED(기본)·WALL_CLOCK
     * @param seed               없으면 시나리오 시드, 그것도 없으면 무작위
     * @param notificationPolicy PREFIX(기본)·SUPPRESS
     */
    public record StartRequest(Long scenarioId, Integer acceleration, String timestampPolicy, Long seed, String notificationPolicy) {
    }

    /** 응답 {runId, status, seed, accelerationEffective} */
    public record StartResponse(String runId, String status, long seed, int accelerationEffective) {
    }

    /** 제어 응답 {runId, status, simClock, progressPct, accelerationEffective} */
    public record ControlResponse(String runId, String status, Instant simClock, double progressPct, int accelerationEffective) {
    }

    @Transactional
    public StartResponse start(long organizationId, long userId, StartRequest req) {
        if (req.scenarioId() == null) {
            throw invalid("scenarioId", "NotNull", "시나리오가 필요합니다");
        }
        int acceleration = req.acceleration() == null ? 1 : req.acceleration();
        AccelerationClock.validate(acceleration);
        TimestampPolicy policy = parsePolicy(req.timestampPolicy());
        String notification = req.notificationPolicy() == null ? "PREFIX" : req.notificationPolicy();
        if (!notification.equals("PREFIX") && !notification.equals("SUPPRESS")) {
            throw invalid("notificationPolicy", "Pattern", "PREFIX|SUPPRESS");
        }
        ScenarioRepository.ScenarioRow row = scenarios.findById(organizationId, req.scenarioId())
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        Scenario scenario = row.scenario();
        if (!scenario.outdoor().runnable()) {
            throw new BusinessException(SimErrorCode.SIM_WEATHER_DATA_MISSING);   // WEATHER·CSV 외기는 M7(SIM-10.01)
        }
        runs.lockOrganization(organizationId);
        if (runs.countActive(organizationId) >= properties.limits().concurrentRuns()) {
            throw new BusinessException(SimErrorCode.SIM_CONCURRENT_RUN_LIMIT);
        }
        if (!runs.findActiveUsingSpaces(organizationId, scenario.spaceIds(), -1).isEmpty()) {
            throw new BusinessException(SimErrorCode.SIM_SPACE_BUSY);
        }
        List<WorldSpace> spaces = factory.spaces(organizationId, scenario.spaceIds());
        if (spaces.size() != scenario.spaceIds().size()) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        // 실행은 언제나 같은 시작 상태(장비 OFF, 배터리 100%)에서 시작한다: 같은 시드 = 같은 결과(SIM-08.03)
        List<WorldDevice> devices = factory.devicesIn(organizationId, scenario.spaceIds()).stream().map(WorldDevice::forRun).toList();
        checkAcceleration(devices.size(), acceleration);
        long seed = req.seed() != null ? req.seed() : scenario.seed() != null ? scenario.seed() : random.nextLong();
        RunPlan plan = new RunPlan(row.id(), scenario.name(), scenario, spaces, devices, properties.tickSec());
        Instant now = clock.instant();
        long runId = runs.insert(organizationId, row.id(), KIND_SCENARIO, acceleration, acceleration, policy, seed, notification,
                scenario.simStartAt(), Json.write(plan), now.plus(Duration.ofDays(properties.retention().days()))
                        .plus(Duration.ofSeconds(scenario.durationSec())), userId);
        insertScenarioFaults(organizationId, runId, scenario);
        RunStatus running = RunStateMachine.next(RunStatus.CREATED, RunAction.START);
        runs.transition(organizationId, runId, RunStatus.CREATED, running, now);
        events.publish(SimEventTypes.run("started"), organizationId, new SimRunChanged(organizationId, runId, row.id(),
                running.name(), scenario.simStartAt(), acceleration, null, null, now));
        return new StartResponse(Long.toString(runId), running.name(), seed, acceleration);
    }

    void checkAcceleration(int deviceCount, int acceleration) {
        RunLimits.checkAcceleration(deviceCount, acceleration, properties.limits().devicesAtMaxAcceleration());
    }

    /** 시나리오 FAULT 트랙을 장애 행으로 풀어 둔다(정답 라벨의 정본, SIM-05.03) */
    private void insertScenarioFaults(long organizationId, long runId, Scenario scenario) {
        for (ScenarioEvent e : scenario.events()) {
            if (!ScenarioEvent.FAULT.equals(e.track())) {
                continue;
            }
            FaultKind kind = FaultKind.valueOf(String.valueOf(e.params().get("kind")));
            Instant until = e.until() != null ? e.until() : e.at().plusSeconds((long) e.number("durationSec", 600));
            Map<String, Object> params = e.params().get("params") instanceof Map<?, ?> m ? toMap(m) : Map.of();
            String targetType = String.valueOf(e.target().getOrDefault("targetType", FaultSpec.DEVICE));
            FaultValidator.validate(kind, targetType, params, until.getEpochSecond() - e.at().getEpochSecond());
            for (Object target : (List<?>) e.target().get("targetIds")) {
                faults.insert(organizationId, runId, new FaultSpec(0, kind, targetType, String.valueOf(target), params, e.at(), until));
            }
        }
    }

    private static Map<String, Object> toMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @Transactional
    public ControlResponse control(long organizationId, long runId, RunAction action) {
        RunRepository.RunRow row = runs.findById(organizationId, runId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        Instant now = clock.instant();
        RunStatus to = RunStateMachine.next(row.status(), action);
        if (action == RunAction.RESET) {
            RunPlan plan = Json.read(row.plan(), RunPlan.class);
            if (runs.reset(organizationId, runId, plan.scenario().simStartAt()) == 0) {
                throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
            }
            for (FaultRepository.FaultRow f : faults.findByRun(organizationId, runId)) {
                faults.updateStatus(organizationId, f.id(), "SCHEDULED", null);
            }
        } else {
            if ((action == RunAction.RESUME || action == RunAction.START) && row.status() == RunStatus.CREATED) {
                RunPlan plan = Json.read(row.plan(), RunPlan.class);
                runs.lockOrganization(organizationId);
                if (runs.countActive(organizationId) >= properties.limits().concurrentRuns()) {
                    throw new BusinessException(SimErrorCode.SIM_CONCURRENT_RUN_LIMIT);
                }
                if (!runs.findActiveUsingSpaces(organizationId, plan.scenario().spaceIds(), runId).isEmpty()) {
                    throw new BusinessException(SimErrorCode.SIM_SPACE_BUSY);
                }
            }
            if (runs.transition(organizationId, runId, row.status(), to, now) == 0) {
                throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
            }
        }
        String suffix = switch (action) {
            case PAUSE -> "paused";
            case RESUME, START -> row.status() == RunStatus.CREATED ? "started" : "resumed";
            case STOP -> "stopped";
            case RESET -> "reset";
            default -> action.name().toLowerCase();
        };
        Instant simClock = executor.simNow(runId).orElse(row.simClock());
        events.publish(SimEventTypes.run(suffix), organizationId, new SimRunChanged(organizationId, runId, row.scenarioId(), to.name(),
                simClock, row.accelerationEffective(), action == RunAction.STOP ? Boolean.TRUE : null, null, now));
        return new ControlResponse(Long.toString(runId), to.name(), simClock, action == RunAction.RESET ? 0 : row.progressPct(),
                row.accelerationEffective());
    }

    /** 실행 중 가속 변경(API-SIM-15 PATCH) */
    @Transactional
    public Map<String, Object> changeAcceleration(long organizationId, long runId, Integer acceleration) {
        if (acceleration == null) {
            throw invalid("acceleration", "NotNull", "가속이 필요합니다");
        }
        AccelerationClock.validate(acceleration);
        RunRepository.RunRow row = runs.findById(organizationId, runId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if (row.status().terminal() || row.status() == RunStatus.PURGED) {
            throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
        }
        RunPlan plan = Json.read(row.plan(), RunPlan.class);
        checkAcceleration(plan.devices().size(), acceleration);
        runs.setAcceleration(organizationId, runId, acceleration, acceleration);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", Long.toString(runId));
        r.put("accelerationRequested", acceleration);
        r.put("accelerationEffective", acceleration);
        r.put("throttled", false);
        return r;
    }

    /** 실행 상태(API-SIM-16) */
    public Map<String, Object> status(long organizationId, long runId) {
        RunRepository.RunRow row = runs.findById(organizationId, runId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        Optional<SimulationExecutor.LiveView> view = executor.view(runId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", Long.toString(runId));
        r.put("kind", row.kind());
        r.put("status", row.status().name());
        r.put("scenarioId", row.scenarioId() == null ? null : Long.toString(row.scenarioId()));
        r.put("accelerationRequested", row.accelerationRequested());
        r.put("accelerationEffective", view.map(SimulationExecutor.LiveView::accelerationEffective).orElse(row.accelerationEffective()));
        r.put("throttled", view.map(SimulationExecutor.LiveView::throttled).orElse(row.accelerationEffective() < row.accelerationRequested()));
        r.put("simClock", view.map(SimulationExecutor.LiveView::simClock).orElse(row.simClock()));
        r.put("startedAt", row.startedAt());
        Instant end = row.finishedAt() != null ? row.finishedAt() : clock.instant();
        r.put("elapsedSec", row.startedAt() == null ? 0 : Math.max(0, Duration.between(row.startedAt(), end).toSeconds()));
        r.put("progressPct", view.map(SimulationExecutor.LiveView::progressPct).orElse(row.progressPct()));
        r.put("seed", row.seed());
        List<Map<String, Object>> expectations = new ArrayList<>();
        if (view.isPresent()) {
            view.get().expectations().forEach((id, p) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("state", p.state);
                m.put("evidence", p.atMs == null ? null : Map.of("at", Instant.ofEpochMilli(p.atMs)));
                expectations.add(m);
            });
        } else if (row.result() != null) {
            Object list = Json.map(row.result()).get("expectations");
            if (list instanceof List<?> l) {
                l.forEach(x -> expectations.add(toMap((Map<?, ?>) x)));
            }
        }
        r.put("expectations", expectations);
        List<Map<String, Object>> last = new ArrayList<>();
        view.ifPresent(v -> v.lastEvents().forEach(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("simAt", n.simAt());
            m.put("type", n.type());
            m.put("message", n.message());
            last.add(m);
        }));
        r.put("lastEvents", last);
        r.put("failureReason", row.failureReason());
        return r;
    }

    /** 실행 결과 리포트(API-SIM-17) */
    public Map<String, Object> report(long organizationId, long runId) {
        RunRepository.RunRow row = runs.findById(organizationId, runId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        Map<String, Object> result = row.result() == null ? new LinkedHashMap<>() : Json.map(row.result());
        RunPlan plan = row.plan() == null ? null : Json.read(row.plan(), RunPlan.class);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", Long.toString(runId));
        r.put("status", row.status().name());
        r.put("scenario", plan == null ? null : plan.scenarioName());
        r.put("seed", row.seed());
        r.put("acceleration", row.accelerationRequested());
        r.put("simFrom", plan == null ? null : plan.scenario().simStartAt());
        r.put("simTo", result.getOrDefault("simTo", row.simClock()));
        r.put("partial", row.partial());
        List<?> expectations = result.get("expectations") instanceof List<?> l ? l : List.of();
        long passed = expectations.stream().filter(x -> x instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("passed"))).count();
        long judged = expectations.stream().filter(x -> x instanceof Map<?, ?> m && !"SKIPPED".equals(m.get("state"))).count();
        r.put("passed", row.status() == RunStatus.COMPLETED && passed == judged);
        r.put("passedCount", passed);
        r.put("total", expectations.size());
        r.put("expectations", expectations);
        r.put("metrics", result.get("metrics"));
        List<Map<String, Object>> faultList = new ArrayList<>();
        for (FaultRepository.FaultRow f : faults.findByRun(organizationId, runId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Long.toString(f.id()));
            m.put("kind", f.spec().kind().name());
            m.put("target", f.spec().targetType() + ":" + f.spec().targetId());
            m.put("from", f.spec().simFrom());
            m.put("to", f.spec().simTo());
            faultList.add(m);
        }
        r.put("faults", faultList);
        r.put("devicesCreated", plan == null ? 0 : plan.devices().size());
        r.put("dataSha256", result.get("dataSha256"));
        r.put("readings", result.get("readings"));
        r.put("retainUntil", row.retainUntil());
        return r;
    }

    /** 보관 연장(API-SIM-17 PATCH, 최대 종료 + 1년) */
    @Transactional
    public Map<String, Object> retain(long organizationId, long runId, Instant retainUntil) {
        RunRepository.RunRow row = runs.findById(organizationId, runId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if (retainUntil == null) {
            throw invalid("retainUntil", "NotNull", "보관 기한이 필요합니다");
        }
        Instant base = row.finishedAt() != null ? row.finishedAt() : clock.instant();
        if (retainUntil.isAfter(base.plus(Duration.ofDays(properties.retention().maxDays())))) {
            throw invalid("retainUntil", "Max", "종료 + " + properties.retention().maxDays() + "일 이내");
        }
        runs.setRetainUntil(organizationId, runId, retainUntil);
        return Map.of("runId", Long.toString(runId), "retainUntil", retainUntil);
    }

    static TimestampPolicy parsePolicy(String raw) {
        if (raw == null) {
            return TimestampPolicy.SIMULATED;
        }
        try {
            return TimestampPolicy.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("timestampPolicy", "Pattern", "SIMULATED|WALL_CLOCK");
        }
    }

    static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
