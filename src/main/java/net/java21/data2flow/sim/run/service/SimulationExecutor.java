package net.java21.data2flow.sim.run.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.sim.command.repository.CommandInboxRepository;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.common.SimDirectory;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.contracts.DeviceCommandAck;
import net.java21.data2flow.sim.contracts.DeviceStateReported;
import net.java21.data2flow.sim.contracts.SimEventTypes;
import net.java21.data2flow.sim.contracts.SimFaultLabel;
import net.java21.data2flow.sim.contracts.SimRunChanged;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.TimestampPolicy;
import net.java21.data2flow.sim.engine.WorldConfig;
import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.engine.WorldSpace;
import net.java21.data2flow.sim.engine.WorldState;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.fault.repository.FaultRepository;
import net.java21.data2flow.sim.output.RawOutput;
import net.java21.data2flow.sim.output.SimEventPublisher;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.SpaceState;
import net.java21.data2flow.sim.run.domain.AccelerationClock;
import net.java21.data2flow.sim.run.domain.Checkpoint;
import net.java21.data2flow.sim.run.domain.RunAction;
import net.java21.data2flow.sim.run.domain.RunPlan;
import net.java21.data2flow.sim.run.domain.RunStateMachine;
import net.java21.data2flow.sim.run.domain.RunStatus;
import net.java21.data2flow.sim.run.domain.ThrottleController;
import net.java21.data2flow.sim.run.repository.LeaseRepository;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실행기(SIM-04.02·04.03, SIM-02 상시 보고). 주기(기본 1초)마다:
 * <ol>
 *   <li>RUNNING·PAUSED 실행을 소유(leases)하고, 없으면 체크포인트에서 불러온다(다른 인스턴스가 죽었으면 넘겨받기, TC-SIM-045)</li>
 *   <li>가속 기준점에서 목표 틱까지 엔진을 진행한다(x60이면 실제 1초에 6틱). 명령 수신함의 명령을 넣는다</li>
 *   <li>원본은 {@code data2flow.raw}에 발행하고 confirm을 모두 받은 뒤에만 다음으로 간다. 실패하면 마지막 정상 상태로 되돌려 같은 메시지를
 *       다시 만든다(같은 시드라 같은 메시지 → 수집 단계 중복 제거)</li>
 *   <li>10틱마다 체크포인트, 끝나면 기대 결과 판정과 리포트</li>
 *   <li>상시 보고(ALWAYS) 기기는 조직별 상시 환경을 실제 시간(x1)으로 돌린다. 시나리오가 쓰는 공간은 뺀다(BR-SIM-17)</li>
 * </ol>
 * 배포 조직({@link SimDirectory})만 돌린다(ADR-030).
 */
public class SimulationExecutor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulationExecutor.class);
    public static final int CHECKPOINT_TICKS = 10;
    private static final Duration CONFIRM_WAIT = Duration.ofSeconds(15);
    private static final int LAST_EVENTS = 20;

    private final SimProperties properties;
    private final SimDirectory directory;
    private final RunRepository runs;
    private final LeaseRepository leases;
    private final FaultRepository faults;
    private final SimDeviceRepository devices;
    private final SpacePhysicsRepository spaces;
    private final CommandInboxRepository inbox;
    private final WorldFactory factory;
    private final RawOutput output;
    private final SimEventPublisher events;
    private final Clock clock;
    private final Map<Long, LiveRun> live = new ConcurrentHashMap<>();
    private final Map<Long, LiveAmbient> ambient = new ConcurrentHashMap<>();
    private final Map<Long, ThrottleController> throttles = new ConcurrentHashMap<>();
    private final Set<Long> dirtyOrganizations = ConcurrentHashMap.newKeySet();
    private final AtomicLong rounds = new AtomicLong();
    private final Counter uplinkCounter;
    private volatile boolean running;
    private volatile Instant lastRoundAt;

    public SimulationExecutor(SimProperties properties, SimDirectory directory, RunRepository runs, LeaseRepository leases,
                              FaultRepository faults, SimDeviceRepository devices, SpacePhysicsRepository spaces,
                              CommandInboxRepository inbox, WorldFactory factory, RawOutput output, SimEventPublisher events,
                              Clock clock, MeterRegistry registry) {
        this.properties = properties;
        this.directory = directory;
        this.runs = runs;
        this.leases = leases;
        this.faults = faults;
        this.devices = devices;
        this.spaces = spaces;
        this.inbox = inbox;
        this.factory = factory;
        this.output = output;
        this.events = events;
        this.clock = clock;
        this.uplinkCounter = Counter.builder("data2flow.sim.uplinks").description("가상 원본 발행 수").register(registry);
        Gauge.builder("data2flow.sim.runs.live", live, Map::size).description("이 인스턴스가 돌리는 실행 수").register(registry);
    }

    /** 이 인스턴스가 돌리는 실행 하나 */
    static final class LiveRun {
        final long runId;
        final long organizationId;
        final RunPlan plan;
        SimulationWorld world;
        AccelerationClock acceleration;
        RunStatus status;
        int requested;
        boolean throttled;
        String lastGood;
        long lastCheckpointTick;
        boolean pauseSaved;
        final ArrayDeque<Emission.Note> lastEvents = new ArrayDeque<>();

        LiveRun(long runId, long organizationId, RunPlan plan) {
            this.runId = runId;
            this.organizationId = organizationId;
            this.plan = plan;
        }
    }

    /** 조직의 상시 환경 */
    static final class LiveAmbient {
        final long organizationId;
        SimulationWorld world;
        String lastGood;
        long lastSaveTick;
        Set<Long> spaceIds = Set.of();
        Set<Long> deviceIds = Set.of();

        LiveAmbient(long organizationId) {
            this.organizationId = organizationId;
        }
    }

    /** 실행 상태 조회용(API-SIM-16) */
    public record LiveView(long runId, RunStatus status, Instant simClock, double progressPct, int accelerationRequested,
                           int accelerationEffective, boolean throttled, long tick, String digest, long readings,
                           List<Emission.Note> lastEvents, Map<String, WorldState.ExpectationProgress> expectations,
                           WorldState.Stats stats) {
    }

    // ───────────────────────────── 주기 ─────────────────────────────

    @Scheduled(fixedDelayString = "${data2flow.sim.executor.period:1s}", initialDelayString = "${data2flow.sim.executor.period:1s}")
    public void scheduled() {
        if (running && properties.executor().enabled()) {
            try {
                round();
            } catch (RuntimeException e) {
                log.warn("실행기 주기 실패: {}", e.toString(), e);
            }
        }
    }

    /** 한 주기(시험은 MutableClock을 움직이고 직접 부른다) */
    public synchronized void round() {
        Instant now = clock.instant();
        lastRoundAt = now;
        Set<Long> orgs = new HashSet<>(directory.organizations());
        Set<Long> seen = new HashSet<>();
        for (RunRepository.RunRow row : runs.listRunnable()) {
            if (!orgs.contains(row.organizationId())) {
                continue;
            }
            seen.add(row.id());
            LiveRun lr = live.get(row.id());
            if (!leases.acquire(lease(row.id()), properties.instanceId(), now, now.plus(properties.executor().lease()))) {
                if (lr != null) {
                    live.remove(row.id());   // 다른 인스턴스가 가져갔다
                }
                continue;
            }
            if (lr == null) {
                lr = load(row, now);
                if (lr == null) {
                    continue;
                }
                live.put(row.id(), lr);
            }
            sync(lr, row, now);
        }
        for (LiveRun lr : List.copyOf(live.values())) {
            if (!seen.contains(lr.runId)) {
                settle(lr, now);
            }
        }
        Map<Long, Integer> uplinksByOrg = new TreeMap<>();
        for (LiveRun lr : new TreeMap<>(live).values()) {
            uplinksByOrg.merge(lr.organizationId, advance(lr, now), Integer::sum);
        }
        if (properties.executor().ambient()) {
            ambientRound(orgs, now);
        }
        throttle(uplinksByOrg, now);
        if (rounds.incrementAndGet() % 3600 == 0) {
            inbox.purgeBefore(now.minus(Duration.ofDays(7)));
        }
    }

    private static String lease(long runId) {
        return "run:" + runId;
    }

    private LiveRun load(RunRepository.RunRow row, Instant now) {
        try {
            RunPlan plan = Json.read(row.plan(), RunPlan.class);
            LiveRun lr = new LiveRun(row.id(), row.organizationId(), plan);
            WorldConfig config = config(row, plan);
            if (row.checkpoint() != null) {
                Checkpoint cp = Json.read(row.checkpoint(), Checkpoint.class);
                lr.world = SimulationWorld.resume(config, cp.world());
                lr.lastCheckpointTick = lr.world.tick();
                log.info("실행 {}을(를) 체크포인트(틱 {})에서 이어 실행합니다", row.id(), lr.world.tick());
            } else {
                lr.world = SimulationWorld.start(config);
            }
            lr.lastGood = lr.world.snapshotJson();
            lr.status = row.status();
            lr.requested = row.accelerationRequested();
            lr.acceleration = new AccelerationClock(now, lr.world.tick(), row.accelerationEffective(), plan.tickSec());
            return lr;
        } catch (RuntimeException e) {
            log.warn("실행 {}을(를) 불러오지 못했습니다: {}", row.id(), e.toString());
            fail(row.organizationId(), row.id(), row.status(), row.simClock(), "LOAD_FAILED: " + e.getMessage(), now);
            return null;
        }
    }

    /** 실행 계획 → 엔진 설정(장애는 DB 정본에서 다시 읽는다) */
    WorldConfig config(RunRepository.RunRow row, RunPlan plan) {
        List<FaultSpec> specs = faults.findByRun(row.organizationId(), row.id()).stream()
                .filter(f -> !"CANCELLED".equals(f.status()) || f.spec().simTo() != null)
                .map(FaultRepository.FaultRow::spec).toList();
        long totalTicks = (long) Math.ceil(plan.scenario().durationSec() / (double) plan.tickSec());
        return new WorldConfig(row.id(), row.organizationId(), row.seed(), plan.scenario().simStartAt(), plan.tickSec(), totalTicks,
                row.timestampPolicy(), ZoneId.of(properties.zone()), plan.scenario().outdoor(), plan.spaces(), plan.devices(),
                plan.scenario().events(), specs, plan.scenario().expectations());
    }

    private void sync(LiveRun lr, RunRepository.RunRow row, Instant now) {
        synchronized (lr) {
            if (lr.status == RunStatus.PAUSED && row.status() == RunStatus.RUNNING) {
                lr.acceleration = lr.acceleration.rebase(now, lr.world.tick());   // 재개: 기준점을 지금으로(TC-SIM-044)
                lr.pauseSaved = false;
                lr.lastEvents.add(new Emission.Note(lr.world.simNow(), "STATUS", "재개"));
            }
            lr.status = row.status();
            lr.requested = row.accelerationRequested();
            if (row.accelerationEffective() != lr.acceleration.acceleration()) {
                lr.acceleration = lr.acceleration.withAcceleration(row.accelerationEffective(), now, lr.world.tick());
            }
        }
    }

    /** 목록에서 빠진 실행: 정지면 끝난 구간까지 판정, 초기화·그 밖이면 내려놓는다 */
    private void settle(LiveRun lr, Instant now) {
        Optional<RunRepository.RunRow> row = runs.loadById(lr.runId);
        synchronized (lr) {
            if (row.isPresent() && row.get().status() == RunStatus.STOPPED) {
                lr.world.finalizeExpectations(true);
                String result = Json.write(result(lr, true));
                runs.finish(lr.organizationId, lr.runId, RunStatus.STOPPED, RunStateMachine.next(RunStatus.STOPPED, RunAction.COMPLETE),
                        lr.world.simNow(), lr.world.progressPct(), true, result, now, retainUntil(now), null);
                publishRun(lr, "completed", RunStatus.COMPLETED, true, null, now);
            }
            live.remove(lr.runId);
            leases.release(lease(lr.runId), properties.instanceId());
        }
    }

    /** @return 이번에 보낸 원본 수 */
    private int advance(LiveRun lr, Instant now) {
        synchronized (lr) {
            if (lr.status == RunStatus.PAUSED) {
                if (!lr.pauseSaved) {
                    checkpoint(lr, now);
                    lr.pauseSaved = true;
                }
                return 0;
            }
            if (lr.status != RunStatus.RUNNING) {
                return 0;
            }
            long total = lr.world.config().totalTicks();
            long target = Math.min(total, lr.acceleration.targetTick(now));
            long steps = Math.min(target - lr.world.tick(), properties.executor().maxTicksPerRound());
            List<WorldState.InboundCommand> taken = takeCommands(lr.world);
            List<Emission> out = new ArrayList<>();
            for (long i = 0; i < steps && !lr.world.finished(); i++) {
                out.addAll(lr.world.step(now));
            }
            if (!dispatch(lr.organizationId, lr.runId, lr.world.config().timestampPolicy(), out, lr.lastEvents)) {
                lr.world = SimulationWorld.resume(lr.world.config(), SimulationWorld.parseSnapshot(lr.lastGood));
                taken.forEach(lr.world::submit);   // 가져온 명령은 되돌린 상태에 다시 넣는다
                lr.acceleration = lr.acceleration.rebase(now, lr.world.tick());
                return 0;
            }
            lr.lastGood = lr.world.snapshotJson();
            if (lr.world.tick() - lr.lastCheckpointTick >= CHECKPOINT_TICKS) {
                checkpoint(lr, now);
            }
            if (lr.world.finished()) {
                complete(lr, now);
            }
            return (int) out.stream().filter(e -> e instanceof Emission.Uplink).count();
        }
    }

    private List<WorldState.InboundCommand> takeCommands(SimulationWorld world) {
        List<Long> actuators = world.devices().stream().filter(WorldDevice::actuator).map(WorldDevice::deviceId).toList();
        List<WorldState.InboundCommand> taken = inbox.take(actuators);
        taken.forEach(world::submit);
        return taken;
    }

    private void checkpoint(LiveRun lr, Instant now) {
        Checkpoint cp = new Checkpoint(lr.acceleration.anchorReal().toEpochMilli(), lr.acceleration.anchorTick(), lr.world.state());
        runs.saveCheckpoint(lr.runId, lr.world.simNow(), lr.world.progressPct(), Json.write(cp), now);
        lr.lastCheckpointTick = lr.world.tick();
    }

    private void complete(LiveRun lr, Instant now) {
        RunStatus evaluating = RunStateMachine.next(RunStatus.RUNNING, RunAction.FINISH);
        if (runs.transition(lr.organizationId, lr.runId, RunStatus.RUNNING, evaluating, now) == 0) {
            return;
        }
        lr.world.finalizeExpectations(false);
        String result = Json.write(result(lr, false));
        runs.finish(lr.organizationId, lr.runId, evaluating, RunStateMachine.next(evaluating, RunAction.COMPLETE), lr.world.simNow(),
                100, false, result, now, retainUntil(now), null);
        publishRun(lr, "completed", RunStatus.COMPLETED, false, null, now);
        live.remove(lr.runId);
        leases.release(lease(lr.runId), properties.instanceId());
        log.info("실행 {} 완료(해시 {})", lr.runId, lr.world.digest());
    }

    private void fail(long organizationId, long runId, RunStatus from, Instant simClock, String reason, Instant now) {
        if (RunStateMachine.allowed(from, RunAction.FAIL)) {
            runs.finish(organizationId, runId, from, RunStatus.FAILED, simClock == null ? now : simClock, 0, false, null, now,
                    retainUntil(now), reason.length() > 500 ? reason.substring(0, 500) : reason);
            events.publish(SimEventTypes.run("failed"), organizationId,
                    new SimRunChanged(organizationId, runId, null, RunStatus.FAILED.name(), simClock, 1, null, reason, now));
        }
    }

    private Instant retainUntil(Instant now) {
        return now.plus(Duration.ofDays(properties.retention().days()));
    }

    void publishRun(LiveRun lr, String suffix, RunStatus status, Boolean partial, String reason, Instant now) {
        events.publish(SimEventTypes.run(suffix), lr.organizationId, new SimRunChanged(lr.organizationId, lr.runId,
                lr.plan.scenarioId(), status.name(), lr.world.simNow(), lr.acceleration.acceleration(), partial, reason, now));
    }

    /** 실행 결과({@code runs.result}, API-SIM-17) */
    Map<String, Object> result(LiveRun lr, boolean partial) {
        SimulationWorld w = lr.world;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", lr.plan.scenarioName());
        r.put("seed", w.config().seed());
        r.put("simFrom", w.config().simStart());
        r.put("simTo", w.simNow());
        r.put("partial", partial);
        r.put("dataSha256", w.digest());
        r.put("readings", w.readingCount());
        r.put("expectations", expectations(lr));
        WorldState.Stats s = w.stats();
        Map<String, Object> metrics = new LinkedHashMap<>();
        long total = s.comfortSec + s.outOfTargetSec;
        metrics.put("comfortScore", total == 0 ? null : Math.round(1000.0 * s.comfortSec / total) / 10.0);
        metrics.put("outOfTargetSec", s.outOfTargetSec);
        metrics.put("energyKwh", Math.round(s.energyKwh * 1000) / 1000.0);
        metrics.put("controlCount", s.controlCount);
        metrics.put("alarmCount", null);
        r.put("metrics", metrics);
        r.put("devicesCreated", lr.plan.devices().size());
        return r;
    }

    List<Map<String, Object>> expectations(LiveRun lr) {
        List<Map<String, Object>> list = new ArrayList<>();
        lr.plan.scenario().expectations().forEach(x -> {
            WorldState.ExpectationProgress p = lr.world.expectations().get(x.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.id());
            m.put("kind", x.kind());
            m.put("state", p == null ? "PENDING" : p.state);
            m.put("passed", p != null && "PASSED".equals(p.state));
            Map<String, Object> evidence = new LinkedHashMap<>();
            if (p != null && p.atMs != null) {
                evidence.put("at", Instant.ofEpochMilli(p.atMs));
            }
            if (p != null && p.value != null) {
                evidence.put("value", p.value);
            }
            m.put("evidence", evidence);
            list.add(m);
        });
        return list;
    }

    // ───────────────────────────── 발행 ─────────────────────────────

    /** 방출을 내보낸다. 원본 confirm이 하나라도 실패하면 false */
    private boolean dispatch(long organizationId, Long runId, TimestampPolicy policy, List<Emission> out,
                             ArrayDeque<Emission.Note> notes) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Emission e : out) {
            if (e instanceof Emission.Uplink u) {
                RawEnvelope env = new RawEnvelope(RawEnvelope.VERSION, u.messageId(), u.organizationId(), u.sourceId(),
                        SourceTypes.SIMULATION, u.topic(), u.payload(), u.receivedAt(), properties.instanceId(), u.dedupKey(), true, runId);
                pending.add(output.publish(env).toCompletableFuture());
            }
        }
        if (!pending.isEmpty()) {
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(CONFIRM_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                uplinkCounter.increment(pending.size());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ex) {
                log.warn("가상 원본 발행 실패({}건): {}. 마지막 정상 상태에서 다시 만듭니다", pending.size(), ex.toString());
                return false;
            }
        }
        for (Emission e : out) {
            switch (e) {
                case Emission.CommandAck a -> events.publish(SimEventTypes.COMMAND_ACK, a.organizationId(),
                        new DeviceCommandAck(a.commandId(), a.deviceId(), a.result(), a.reason(), a.at(), true));
                case Emission.StateReported r -> {
                    events.publish(SimEventTypes.STATE_REPORTED, r.organizationId(),
                            new DeviceStateReported(r.deviceId(), r.version(), r.capabilities(), r.at(), true));
                }
                case Emission.FaultLabel f -> {
                    FaultSpec spec = f.fault();
                    if (spec.faultId() > 0) {
                        faults.updateStatus(organizationId, spec.faultId(), f.started() ? "ACTIVE" : "ENDED", f.started() ? null : f.at());
                    }
                    events.publish(f.started() ? SimEventTypes.FAULT_STARTED : SimEventTypes.FAULT_ENDED, organizationId,
                            new SimFaultLabel(organizationId, runId, spec.faultId(), spec.kind().name(), spec.targetType(),
                                    spec.targetId(), spec.simFrom(), spec.simTo(), spec.params()));
                }
                case Emission.Note n -> {
                    if (notes != null) {
                        notes.add(n);
                        while (notes.size() > LAST_EVENTS) {
                            notes.pollFirst();
                        }
                    }
                }
                default -> {
                }
            }
        }
        return true;
    }

    // ───────────────────────────── 상시 환경 ─────────────────────────────

    private void ambientRound(Set<Long> orgs, Instant now) {
        Set<Long> withAlways = new HashSet<>(devices.listOrganizationsWithAlwaysDevices());
        for (Long org : List.copyOf(ambient.keySet())) {
            if (!orgs.contains(org) || !withAlways.contains(org)) {
                ambient.remove(org);
                leases.release("ambient:" + org, properties.instanceId());
            }
        }
        for (long org : new java.util.TreeSet<>(orgs)) {
            if (!withAlways.contains(org)) {
                continue;
            }
            if (!leases.acquire("ambient:" + org, properties.instanceId(), now, now.plus(properties.executor().lease()))) {
                ambient.remove(org);
                continue;
            }
            LiveAmbient la = ambient.computeIfAbsent(org, LiveAmbient::new);
            synchronized (la) {
                advanceAmbient(la, now);
            }
        }
    }

    private void advanceAmbient(LiveAmbient la, Instant now) {
        int tickSec = properties.tickSec();
        boolean reload = la.world == null || dirtyOrganizations.remove(la.organizationId) || la.world.tick() % 6 == 0;
        if (reload) {
            Set<Long> busy = new HashSet<>(runs.findBusySpaces(la.organizationId));
            List<Long> spaceIds = spaces.findAll(la.organizationId).stream().map(s -> s.spaceId()).filter(id -> !busy.contains(id)).toList();
            List<WorldSpace> ws = factory.spaces(la.organizationId, spaceIds);
            List<WorldDevice> wd = devices.findAlways(la.organizationId).stream().filter(d -> spaceIds.contains(d.spaceId()))
                    .map(factory::device).toList();
            Set<Long> sIds = new HashSet<>(spaceIds);
            Set<Long> dIds = new HashSet<>(wd.stream().map(WorldDevice::deviceId).toList());
            if (la.world == null) {
                long start = Math.floorDiv(now.getEpochSecond(), tickSec) * tickSec;
                la.world = SimulationWorld.start(new WorldConfig(null, la.organizationId, la.organizationId, Instant.ofEpochSecond(start),
                        tickSec, 0, TimestampPolicy.SIMULATED, ZoneId.of(properties.zone()), new OutdoorSpec(null, null, null, null), ws,
                        wd, List.of(), standingFaults(la.organizationId), List.of()));
                la.lastGood = la.world.snapshotJson();
            } else if (!sIds.equals(la.spaceIds) || !dIds.equals(la.deviceIds) || reload) {
                WorldConfig c = la.world.config();
                la.world = la.world.reconfigure(new WorldConfig(null, c.organizationId(), c.seed(), c.simStart(), c.tickSec(), 0,
                        c.timestampPolicy(), c.zone(), c.outdoor(), ws, wd, List.of(), standingFaults(la.organizationId), List.of()));
            }
            la.spaceIds = sIds;
            la.deviceIds = dIds;
        }
        long target = (now.toEpochMilli() - la.world.config().simStart().toEpochMilli()) / (tickSec * 1000L);
        long steps = Math.min(target - la.world.tick(), properties.executor().maxTicksPerRound());
        if (steps <= 0) {
            return;
        }
        List<WorldState.InboundCommand> taken = takeCommands(la.world);
        List<Emission> out = new ArrayList<>();
        for (long i = 0; i < steps; i++) {
            out.addAll(la.world.step(now));
        }
        if (!dispatch(la.organizationId, null, TimestampPolicy.SIMULATED, out, null)) {
            la.world = SimulationWorld.resume(la.world.config(), SimulationWorld.parseSnapshot(la.lastGood));
            taken.forEach(la.world::submit);
            return;
        }
        la.lastGood = la.world.snapshotJson();
        boolean commanded = out.stream().anyMatch(e -> e instanceof Emission.StateReported);
        if (la.world.tick() - la.lastSaveTick >= CHECKPOINT_TICKS || commanded) {
            saveRuntime(la.world);
            la.lastSaveTick = la.world.tick();
        }
    }

    private List<FaultSpec> standingFaults(long organizationId) {
        return faults.findStanding(organizationId).stream().map(FaultRepository.FaultRow::spec).toList();
    }

    private void saveRuntime(SimulationWorld world) {
        for (WorldDevice d : world.devices()) {
            if (d.actuator()) {
                WorldState.ActuatorRuntime rt = world.actuatorRuntime(d.deviceId());
                devices.saveRuntime(d.deviceId(), rt.frameCounter, null, rt.reported);
            } else {
                WorldState.SensorRuntime rt = world.sensorRuntime(d.deviceId());
                devices.saveRuntime(d.deviceId(), rt.frameCounter, rt.battery, null);
            }
        }
    }

    // ───────────────────────────── 속도 제한 ─────────────────────────────

    private void throttle(Map<Long, Integer> uplinksByOrg, Instant now) {
        for (Map.Entry<Long, Integer> e : uplinksByOrg.entrySet()) {
            ThrottleController t = throttles.computeIfAbsent(e.getKey(),
                    k -> new ThrottleController(properties.limits().designIngestPerSec(), properties.limits().virtualShare()));
            double seconds = Math.max(1.0, properties.executor().period().toMillis() / 1000.0);
            double rate = e.getValue() / seconds;
            for (LiveRun lr : live.values()) {
                if (lr.organizationId != e.getKey() || lr.status != RunStatus.RUNNING) {
                    continue;
                }
                synchronized (lr) {
                    ThrottleController.Decision d = t.decide(now.toEpochMilli(), lr.acceleration.acceleration(), lr.requested, rate, 0);
                    if (d.changed()) {
                        lr.throttled = d.to() < lr.requested;
                        lr.acceleration = lr.acceleration.withAcceleration(d.to(), now, lr.world.tick());
                        runs.setAcceleration(lr.organizationId, lr.runId, null, d.to());
                        publishRun(lr, "throttled", RunStatus.RUNNING, null, d.reason(), now);
                        lr.lastEvents.add(new Emission.Note(lr.world.simNow(), "THROTTLE", "가속 x" + d.from() + " → x" + d.to()
                                + " (" + d.reason() + ", 초당 " + Math.round(d.rate()) + "건)"));
                    }
                }
                break;
            }
        }
    }

    // ───────────────────────────── 서비스가 부르는 것 ─────────────────────────────

    /** 기기·공간 설정이 바뀌었다: 상시 환경을 다시 읽고, 실행 중인 세계의 그 기기 설정을 바꾼다(다음 보고부터) */
    public void devicesChanged(long organizationId, Collection<Long> deviceIds) {
        dirtyOrganizations.add(organizationId);
        for (LiveRun lr : live.values()) {
            if (lr.organizationId != organizationId) {
                continue;
            }
            synchronized (lr) {
                boolean touched = deviceIds.stream().anyMatch(lr.world::contains);
                if (!touched) {
                    continue;
                }
                List<WorldDevice> updated = new ArrayList<>();
                for (WorldDevice d : lr.world.devices()) {
                    if (deviceIds.contains(d.deviceId())) {
                        devices.findById(organizationId, d.deviceId()).map(factory::device).ifPresent(updated::add);
                    } else {
                        updated.add(d);
                    }
                }
                WorldConfig c = lr.world.config();
                lr.world = lr.world.reconfigure(new WorldConfig(c.runId(), c.organizationId(), c.seed(), c.simStart(), c.tickSec(),
                        c.totalTicks(), c.timestampPolicy(), c.zone(), c.outdoor(), c.spaces(), updated, c.events(), c.faults(),
                        c.expectations()));
            }
        }
    }

    /** 실행 중 장애 주입(SIM-05.03). 이 인스턴스가 그 세계를 돌리면 바로 넣는다 */
    public boolean addFault(long organizationId, Long runId, FaultSpec fault) {
        if (runId != null) {
            LiveRun lr = live.get(runId);
            if (lr != null) {
                synchronized (lr) {
                    lr.world.addFault(fault);
                    return true;
                }
            }
            return false;
        }
        LiveAmbient la = ambient.get(organizationId);
        if (la != null && la.world != null) {
            synchronized (la) {
                la.world.addFault(fault);
                return true;
            }
        }
        return false;
    }

    /** 장애 해제(API-SIM-21). 해제 시각(시뮬레이션) */
    public Optional<Instant> cancelFault(long organizationId, Long runId, long faultId) {
        if (runId != null) {
            LiveRun lr = live.get(runId);
            if (lr != null) {
                synchronized (lr) {
                    Instant at = lr.world.simNow();
                    lr.world.cancelFault(faultId, at);
                    return Optional.of(at);
                }
            }
            return Optional.empty();
        }
        LiveAmbient la = ambient.get(organizationId);
        if (la != null && la.world != null) {
            synchronized (la) {
                Instant at = la.world.simNow();
                la.world.cancelFault(faultId, at);
                return Optional.of(at);
            }
        }
        return Optional.empty();
    }

    /** 실행의 현재 시뮬레이션 시각(이 인스턴스가 돌릴 때) */
    public Optional<Instant> simNow(long runId) {
        LiveRun lr = live.get(runId);
        if (lr == null) {
            return Optional.empty();
        }
        synchronized (lr) {
            return Optional.of(lr.world.simNow());
        }
    }

    public Optional<LiveView> view(long runId) {
        LiveRun lr = live.get(runId);
        if (lr == null) {
            return Optional.empty();
        }
        synchronized (lr) {
            return Optional.of(new LiveView(lr.runId, lr.status, lr.world.simNow(), lr.world.progressPct(), lr.requested,
                    lr.acceleration.acceleration(), lr.throttled, lr.world.tick(), lr.world.digest(), lr.world.readingCount(),
                    List.copyOf(lr.lastEvents), new TreeMap<>(lr.world.expectations()), lr.world.stats()));
        }
    }

    /** 장비를 돌리는 세계가 이 인스턴스에 있으면 그 보고 상태 */
    public Optional<net.java21.data2flow.sim.actuator.domain.ActuatorState> actuatorState(long deviceId) {
        for (LiveRun lr : live.values()) {
            synchronized (lr) {
                if (lr.world.contains(deviceId)) {
                    return Optional.ofNullable(lr.world.actuatorState(deviceId)).map(net.java21.data2flow.sim.actuator.domain.ActuatorState::copy);
                }
            }
        }
        for (LiveAmbient la : ambient.values()) {
            synchronized (la) {
                if (la.world != null && la.world.contains(deviceId)) {
                    return Optional.ofNullable(la.world.actuatorState(deviceId)).map(net.java21.data2flow.sim.actuator.domain.ActuatorState::copy);
                }
            }
        }
        return Optional.empty();
    }

    /** 공간의 지금 물리 상태(실행 우선, 없으면 상시 환경) */
    public Optional<SpaceState> spaceState(long organizationId, long spaceId) {
        for (LiveRun lr : live.values()) {
            if (lr.organizationId == organizationId) {
                synchronized (lr) {
                    SpaceState s = lr.world.space(spaceId);
                    if (s != null) {
                        return Optional.of(s.copy());
                    }
                }
            }
        }
        LiveAmbient la = ambient.get(organizationId);
        if (la != null) {
            synchronized (la) {
                if (la.world != null && la.world.space(spaceId) != null) {
                    return Optional.of(la.world.space(spaceId).copy());
                }
            }
        }
        return Optional.empty();
    }

    /** 장비가 지금 어떤 세계(실행·상시)에서 돌고 있는가(이 인스턴스 또는 다른 인스턴스가 받아 갈 예정) */
    public boolean simulatedSomewhere(long organizationId, long deviceId, boolean always, long spaceId) {
        if (live.values().stream().anyMatch(lr -> lr.world.contains(deviceId))) {
            return true;
        }
        if (runs.findBusySpaces(organizationId).contains(spaceId)) {
            return true;
        }
        return always && properties.executor().enabled() && properties.executor().ambient() && directory.allowed(organizationId);
    }

    public Instant lastRoundAt() {
        return lastRoundAt;
    }

    // ───────────────────────────── 생명주기 ─────────────────────────────

    @Override
    public void start() {
        running = true;
    }

    /** 종료: 돌던 실행의 체크포인트를 남기고 소유를 내려놓는다(다른 인스턴스가 바로 이어 실행) */
    @Override
    public void stop() {
        running = false;
        Instant now = clock.instant();
        for (LiveRun lr : List.copyOf(live.values())) {
            synchronized (lr) {
                try {
                    if (lr.status == RunStatus.RUNNING || lr.status == RunStatus.PAUSED) {
                        checkpoint(lr, now);
                    }
                    leases.release(lease(lr.runId), properties.instanceId());
                } catch (RuntimeException e) {
                    log.warn("종료 중 체크포인트 실패: {}", e.toString());
                }
            }
        }
        for (LiveAmbient la : ambient.values()) {
            try {
                if (la.world != null) {
                    saveRuntime(la.world);
                }
                leases.release("ambient:" + la.organizationId, properties.instanceId());
            } catch (RuntimeException e) {
                log.warn("종료 중 상시 환경 저장 실패: {}", e.toString());
            }
        }
        live.clear();
        ambient.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 원본 생산자(phase −200)보다 먼저 멈춘다 */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 100;
    }

    /** 시험: 실행·상시 환경을 모두 내려놓는다 */
    public void reset() {
        live.clear();
        ambient.clear();
        throttles.clear();
        dirtyOrganizations.clear();
    }
}
