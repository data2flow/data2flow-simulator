package net.java21.data2flow.sim.engine;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.sim.actuator.domain.ActuatorPhysics;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.actuator.domain.CommandInterpreter;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.MetricDef;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.payload.EncodedUplink;
import net.java21.data2flow.sim.payload.PayloadEncoder;
import net.java21.data2flow.sim.payload.UplinkFrame;
import net.java21.data2flow.sim.physics.domain.ActuatorEffects;
import net.java21.data2flow.sim.physics.domain.Outdoor;
import net.java21.data2flow.sim.physics.domain.PhysicsModel;
import net.java21.data2flow.sim.physics.domain.SolarModel;
import net.java21.data2flow.sim.physics.domain.SpaceState;
import net.java21.data2flow.sim.random.SimRandom;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import net.java21.data2flow.sim.sensor.domain.BatteryModel;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;
import net.java21.data2flow.sim.sensor.domain.GeneratorState;
import net.java21.data2flow.sim.sensor.domain.Generators;
import net.java21.data2flow.sim.sensor.domain.ReportScheduler;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 시뮬레이션 엔진(SIM-01~05, SIM-08.03). 시나리오 이벤트 → 명령 → 공간 물리 → 장비 전력 → 센서 보고 → 장애 → 전송 순서로 틱을 진행한다.
 *
 * <p><b>결정적 재현:</b> 시스템 시계·전역 난수를 쓰지 않고, 모든 난수는 (시드, 기기 이름, 용도, 순번)으로 정한다. 기기는 이름 순서로
 * 처리하고, 생성 값은 (기기 이름, 측정 키, 시뮬레이션 시각, 값)의 연쇄 SHA-256({@link WorldState#digest})으로 남긴다. 같은 시드면 가속·틱
 * 실행 간격·인스턴스와 상관없이 같은 해시가 나온다(TC-SIM-088·089). 실제 시각은 측정 시각 정책이 WALL_CLOCK일 때 payload 시각에만 쓴다.
 *
 * <p>스레드 안전하지 않다. 실행기 한 스레드가 {@link #step}을 부르고, 명령 제출({@link #submit})은 같은 잠금 안에서 한다.
 */
public final class SimulationWorld {

    /** 센서 응답 지연 이력 길이(틱). 10초 틱이면 11분 */
    public static final int HISTORY_TICKS = 66;
    /** 목표 범위(쾌적도·이탈 시간 계산) */
    public static final double COMFORT_MIN = 22;
    public static final double COMFORT_MAX = 26;
    public static final double COMFORT_CO2_MAX = 1000;
    private static final long YEAR_MS = 365L * 86_400_000L;
    private static final JsonMapper SNAPSHOT_MAPPER = MessageCodec.newMapper();

    private final WorldConfig config;
    private final WorldState state;
    private final SolarModel sun = SolarModel.seoul();
    private final List<WorldDevice> devices;
    private final Map<Long, WorldDevice> byId = new TreeMap<>();
    private final Map<Long, String> rngKeys = new TreeMap<>();
    private final Map<Long, WorldSpace> spaces = new TreeMap<>();
    private final List<Action> actions;
    private final Map<Long, PhysicsModel.StepResult> lastHvac = new TreeMap<>();
    private final String runKey;
    private final List<Reading> recorder;

    /** 시나리오 동작(OCCUPANCY·OPENING 시작/끝, ACTUATOR) */
    record Action(long atMs, int order, String type, ScenarioEvent event) {
    }

    /** 생성 값 하나(결정성 해시 단위) */
    public record Reading(String device, String metric, Instant simTime, double value) {
    }

    private SimulationWorld(WorldConfig config, WorldState state, boolean record) {
        this.config = config;
        this.state = state;
        this.runKey = config.runId() == null ? "ambient" : "run-" + config.runId();
        this.recorder = record ? new ArrayList<>() : null;
        config.spaces().forEach(s -> spaces.put(s.spaceId(), s));
        List<WorldDevice> sorted = new ArrayList<>(config.devices());
        sorted.sort(Comparator.comparing(WorldDevice::name).thenComparingLong(WorldDevice::deviceId));
        Map<String, Integer> seen = new TreeMap<>();
        for (WorldDevice d : sorted) {
            int n = seen.merge(d.name(), 1, Integer::sum);
            rngKeys.put(d.deviceId(), n == 1 ? d.name() : d.name() + "#" + n);
            byId.put(d.deviceId(), d);
        }
        this.devices = List.copyOf(sorted);
        this.actions = compile(config.events());
    }

    /** 처음부터 시작 */
    public static SimulationWorld start(WorldConfig config) {
        return start(config, false);
    }

    /** @param record 생성 값을 모두 기록(시험·미리 보기용) */
    public static SimulationWorld start(WorldConfig config, boolean record) {
        WorldState s = new WorldState();
        SimulationWorld w = new SimulationWorld(config, s, record);
        w.initialize();
        return w;
    }

    /** 체크포인트에서 이어 실행 */
    public static SimulationWorld resume(WorldConfig config, WorldState state) {
        SimulationWorld w = new SimulationWorld(config, state, false);
        w.reconcile();
        return w;
    }

    /** 상시 환경에서 기기·공간 구성이 바뀌었을 때: 남는 기기의 상태는 유지하고 새 기기만 초기화 */
    public SimulationWorld reconfigure(WorldConfig newConfig) {
        SimulationWorld w = new SimulationWorld(newConfig, state, recorder != null);
        w.reconcile();
        return w;
    }

    private void initialize() {
        state.tick = 0;
        for (WorldSpace s : config.spaces()) {
            state.spaces.put(s.spaceId(), SpaceState.initial(s.physics()));
        }
        for (WorldDevice d : devices) {
            initDevice(d, config.simStart().toEpochMilli());
        }
        for (FaultSpec f : config.faults()) {
            addFaultInternal(f);
        }
        for (Expectation x : config.expectations()) {
            WorldState.ExpectationProgress p = new WorldState.ExpectationProgress();
            if ("ALARM_COUNT".equals(x.kind())) {
                p.state = "SKIPPED";
            }
            state.expectations.put(x.id(), p);
        }
    }

    private void reconcile() {
        long now = simNow().toEpochMilli();
        for (WorldSpace s : config.spaces()) {
            state.spaces.computeIfAbsent(s.spaceId(), id -> SpaceState.initial(s.physics()));
        }
        state.spaces.keySet().retainAll(spaces.keySet());
        state.history.keySet().retainAll(spaces.keySet());
        for (WorldDevice d : devices) {
            boolean known = d.actuator() ? state.actuators.containsKey(d.deviceId()) : state.sensors.containsKey(d.deviceId());
            if (!known) {
                initDevice(d, now);
            }
        }
        state.sensors.keySet().retainAll(byId.keySet());
        state.actuators.keySet().retainAll(byId.keySet());
        for (FaultSpec f : config.faults()) {
            if (state.faults.stream().noneMatch(x -> x.faultId() == f.faultId())) {
                addFaultInternal(f);
            }
        }
    }

    private void initDevice(WorldDevice d, long startMs) {
        String key = rngKeys.get(d.deviceId());
        long seed = d.seedOr(config.seed());
        long first = startMs + ReportScheduler.firstOffsetMs(d.reportIntervalSec(), seed, key);
        if (d.actuator()) {
            WorldState.ActuatorRuntime rt = new WorldState.ActuatorRuntime();
            rt.reported = d.actuatorState() != null ? d.actuatorState().copy() : ActuatorState.initial(d.type().capabilities());
            rt.effective = rt.reported.copy();
            rt.frameCounter = d.frameCounter();
            rt.nextReportAtMs = first;
            state.actuators.put(d.deviceId(), rt);
        } else {
            WorldState.SensorRuntime rt = new WorldState.SensorRuntime();
            rt.frameCounter = d.frameCounter();
            rt.battery = d.batteryPct() == null ? 100 : d.batteryPct();
            rt.nextReportAtMs = d.type().reportOnChange() ? startMs + (long) d.property("heartbeatSec", 3600) * 1000 : first;
            state.sensors.put(d.deviceId(), rt);
        }
    }

    private static List<Action> compile(List<ScenarioEvent> events) {
        List<ScenarioEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(ScenarioEvent::at).thenComparing(e -> e.id() == null ? "" : e.id()));
        List<Action> list = new ArrayList<>();
        int order = 0;
        for (ScenarioEvent e : sorted) {
            switch (e.track()) {
                case ScenarioEvent.OCCUPANCY, ScenarioEvent.OPENING -> {
                    list.add(new Action(e.at().toEpochMilli(), order++, e.track() + ":START", e));
                    if (e.until() != null) {
                        list.add(new Action(e.until().toEpochMilli(), order++, e.track() + ":END", e));
                    }
                }
                case ScenarioEvent.ACTUATOR -> list.add(new Action(e.at().toEpochMilli(), order++, e.track(), e));
                default -> {
                    // FAULT 트랙은 실행 서비스가 장애(faults)로 풀어 넣는다
                }
            }
        }
        list.sort(Comparator.comparingLong(Action::atMs).thenComparingInt(Action::order));
        return List.copyOf(list);
    }

    // ───────────────────────────── 조회 ─────────────────────────────

    public WorldConfig config() {
        return config;
    }

    public long tick() {
        return state.tick;
    }

    public Instant simNow() {
        return config.simStart().plusMillis(state.tick * config.tickSec() * 1000L);
    }

    public boolean finished() {
        return config.totalTicks() > 0 && state.tick >= config.totalTicks();
    }

    public double progressPct() {
        return config.totalTicks() <= 0 ? 0 : Math.min(100.0, state.tick * 100.0 / config.totalTicks());
    }

    public SpaceState space(long spaceId) {
        return state.spaces.get(spaceId);
    }

    public boolean contains(long deviceId) {
        return byId.containsKey(deviceId);
    }

    public List<WorldDevice> devices() {
        return devices;
    }

    public ActuatorState actuatorState(long deviceId) {
        WorldState.ActuatorRuntime rt = state.actuators.get(deviceId);
        return rt == null ? null : rt.reported;
    }

    public WorldState.SensorRuntime sensorRuntime(long deviceId) {
        return state.sensors.get(deviceId);
    }

    public WorldState.ActuatorRuntime actuatorRuntime(long deviceId) {
        return state.actuators.get(deviceId);
    }

    public String digest() {
        return state.digest;
    }

    public long readingCount() {
        return state.readingCount;
    }

    public WorldState.Stats stats() {
        return state.stats;
    }

    public Map<String, WorldState.ExpectationProgress> expectations() {
        return state.expectations;
    }

    public List<FaultSpec> faults() {
        return List.copyOf(state.faults);
    }

    /** 기록 모드에서 모은 생성 값 */
    public List<Reading> readings() {
        return recorder == null ? List.of() : List.copyOf(recorder);
    }

    /** 현재 상태(체크포인트 직렬화용. 실행기 스레드에서만 읽는다) */
    public WorldState state() {
        return state;
    }

    /** 상태 깊은 복사(체크포인트 JSON) */
    public String snapshotJson() {
        return SNAPSHOT_MAPPER.writeValueAsString(state);
    }

    public static WorldState parseSnapshot(String json) {
        return SNAPSHOT_MAPPER.readValue(json, WorldState.class);
    }

    // ───────────────────────────── 입력 ─────────────────────────────

    /** 명령 제출(다음 틱에 처리). 수신 시각은 지금 시뮬레이션 시각 */
    public void submit(WorldState.InboundCommand command) {
        if (command.receivedAtMs == 0) {
            command.receivedAtMs = simNow().toEpochMilli();
        }
        state.inbound.add(command);
    }

    /** 실행 중 장애 주입(SIM-05.03) */
    public void addFault(FaultSpec fault) {
        addFaultInternal(fault);
    }

    private void addFaultInternal(FaultSpec fault) {
        state.faults.removeIf(f -> f.faultId() == fault.faultId());
        state.faults.add(fault);
        state.faults.sort(Comparator.comparingLong(FaultSpec::faultId));
        state.faultRuntime.putIfAbsent(fault.faultId(), new WorldState.FaultRuntime());
    }

    /** 장애 해제(API-SIM-21): 정답 라벨의 끝을 해제 시각으로 고정 */
    public FaultSpec cancelFault(long faultId, Instant at) {
        for (int i = 0; i < state.faults.size(); i++) {
            FaultSpec f = state.faults.get(i);
            if (f.faultId() == faultId) {
                Instant end = f.simTo() == null || at.isBefore(f.simTo()) ? at : f.simTo();
                if (end.isBefore(f.simFrom())) {
                    end = f.simFrom();
                }
                FaultSpec changed = f.endingAt(end);
                state.faults.set(i, changed);
                return changed;
            }
        }
        return null;
    }

    // ───────────────────────────── 진행 ─────────────────────────────

    /**
     * 한 틱 진행한다.
     *
     * @param realNow 실제 현재 시각(측정 시각 정책 WALL_CLOCK일 때만 쓴다)
     */
    public List<Emission> step(Instant realNow) {
        long dtMs = config.tickSec() * 1000L;
        long startMs = config.simStart().toEpochMilli() + state.tick * dtMs;
        long endMs = startMs + dtMs;
        Instant end = Instant.ofEpochMilli(endMs);
        List<Emission> out = new ArrayList<>();

        runActions(endMs, out);
        updateFaultLabels(startMs, endMs, out);
        processCommands(startMs, endMs, out);
        for (WorldState.ActuatorRuntime rt : state.actuators.values()) {
            for (var it = rt.pendingEffects.iterator(); it.hasNext(); ) {
                WorldState.PendingEffect p = it.next();
                if (p.atMs < endMs) {
                    rt.effective = p.state;
                    it.remove();
                }
            }
        }
        stepPhysics(startMs, endMs);
        accumulateEnergy(dtMs);
        for (WorldDevice d : devices) {
            if (!d.actuator()) {
                WorldState.SensorRuntime rt = state.sensors.get(d.deviceId());
                for (Map.Entry<String, MetricSource> e : sources(d).entrySet()) {
                    if (e.getValue().generator() != null) {
                        GeneratorState gs = rt.generators.computeIfAbsent(e.getKey(), k -> new GeneratorState());
                        Generators.tick(e.getValue().generator(), gs, d.seedOr(config.seed()), rngKeys.get(d.deviceId()),
                                e.getKey(), state.tick, end, config.tickSec());
                    }
                }
            }
        }
        recordHistory(endMs);
        updateStats(dtMs, endMs);

        List<Reading> tickReadings = new ArrayList<>();
        for (WorldDevice d : devices) {
            if (d.actuator()) {
                reportActuator(d, endMs, realNow, out, tickReadings);
            } else {
                reportSensor(d, endMs, realNow, out, tickReadings);
            }
        }
        flushPending(endMs, out);
        appendDigest(tickReadings);
        state.tick++;
        return out;
    }

    private void runActions(long endMs, List<Emission> out) {
        while (state.nextAction < actions.size() && actions.get(state.nextAction).atMs() < endMs) {
            Action a = actions.get(state.nextAction++);
            ScenarioEvent e = a.event();
            Instant at = Instant.ofEpochMilli(a.atMs());
            switch (a.type()) {
                case "OCCUPANCY:START" -> {
                    SpaceState s = state.spaces.get(e.targetLong("spaceId"));
                    if (s != null) {
                        s.occupancy = (int) e.number("count", 0);
                        if (e.params().get("activity") instanceof Number n) {
                            s.activityLevel = n.doubleValue();
                        }
                        out.add(new Emission.Note(at, "OCCUPANCY", "공간 " + e.targetLong("spaceId") + " 재실 " + s.occupancy + "명"));
                    }
                }
                case "OCCUPANCY:END" -> {
                    SpaceState s = state.spaces.get(e.targetLong("spaceId"));
                    if (s != null) {
                        s.occupancy = 0;
                        out.add(new Emission.Note(at, "OCCUPANCY", "공간 " + e.targetLong("spaceId") + " 재실 0명"));
                    }
                }
                case "OPENING:START", "OPENING:END" -> opening(e, a.type().endsWith("START") == !Boolean.FALSE.equals(e.params().get("open")), at, out);
                case ScenarioEvent.ACTUATOR -> {
                    WorldState.InboundCommand c = new WorldState.InboundCommand();
                    c.commandId = "scenario-" + (e.id() == null ? a.order() : e.id());
                    c.deviceId = e.targetLong("deviceId") == null ? 0 : e.targetLong("deviceId");
                    c.capability = String.valueOf(e.params().get("capability"));
                    c.command = e.params().get("command") == null ? "set" : String.valueOf(e.params().get("command"));
                    if (e.params().get("args") instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> c.args.put(String.valueOf(k), v));
                    }
                    c.source = "SCENARIO";
                    c.receivedAtMs = a.atMs();
                    state.inbound.add(c);
                }
                default -> {
                }
            }
        }
    }

    private void opening(ScenarioEvent e, boolean open, Instant at, List<Emission> out) {
        boolean window = "WINDOW".equals(e.params().get("opening"));
        Long deviceId = e.targetLong("deviceId");
        Long spaceId = e.targetLong("spaceId");
        if (deviceId != null && byId.containsKey(deviceId)) {
            WorldDevice d = byId.get(deviceId);
            spaceId = d.spaceId();
            if (BuiltinCatalog.WINDOW.equals(d.type().key())) {
                window = true;
            }
        }
        SpaceState s = spaceId == null ? null : state.spaces.get(spaceId);
        if (s == null) {
            return;
        }
        if (window) {
            s.windowOpen = open;
        } else {
            s.doorOpen = open;
        }
        out.add(new Emission.Note(at, "OPENING", "공간 " + spaceId + (window ? " 창문 " : " 문 ") + (open ? "열림" : "닫힘")));
    }

    private void updateFaultLabels(long startMs, long endMs, List<Emission> out) {
        for (FaultSpec f : state.faults) {
            WorldState.FaultRuntime rt = state.faultRuntime.computeIfAbsent(f.faultId(), k -> new WorldState.FaultRuntime());
            if (!rt.started && f.simFrom().toEpochMilli() < endMs) {
                rt.started = true;
                state.stats.faultsStarted++;
                out.add(new Emission.FaultLabel(true, f, f.simFrom()));
                out.add(new Emission.Note(f.simFrom(), "FAULT", f.kind() + " 시작: " + f.targetType() + " " + f.targetId()));
            }
            if (rt.started && !rt.ended && f.simTo() != null && f.simTo().toEpochMilli() < endMs) {
                rt.ended = true;
                out.add(new Emission.FaultLabel(false, f, f.simTo()));
                out.add(new Emission.Note(f.simTo(), "FAULT", f.kind() + " 끝: " + f.targetType() + " " + f.targetId()));
            }
            if (f.kind() == FaultKind.BATTERY_DRAIN && FaultSpec.DEVICE.equals(f.targetType())) {
                long from = Math.max(startMs, f.simFrom().toEpochMilli());
                long to = Math.min(endMs, f.simTo() == null ? endMs : f.simTo().toEpochMilli());
                WorldState.SensorRuntime s = parseId(f.targetId()) == null ? null : state.sensors.get(parseId(f.targetId()));
                if (s != null && to > from) {
                    s.battery = Math.max(0, s.battery - f.number("pctPerHour", 10) * (to - from) / 3_600_000.0);
                }
            }
        }
    }

    private void processCommands(long startMs, long endMs, List<Emission> out) {
        if (state.inbound.isEmpty()) {
            return;
        }
        List<WorldState.InboundCommand> due = new ArrayList<>();
        for (var it = state.inbound.iterator(); it.hasNext(); ) {
            WorldState.InboundCommand c = it.next();
            if (c.receivedAtMs < endMs) {
                due.add(c);
                it.remove();
            }
        }
        due.sort(Comparator.comparingLong((WorldState.InboundCommand c) -> c.receivedAtMs).thenComparing(c -> c.commandId));
        for (WorldState.InboundCommand c : due) {
            WorldDevice d = byId.get(c.deviceId);
            long t = Math.max(c.receivedAtMs, startMs);
            if (d == null || !d.actuator()) {
                out.add(new Emission.CommandAck(c.commandId, c.deviceId, config.organizationId(), "FAILED", "DEVICE_NOT_SIMULATED",
                        Instant.ofEpochMilli(t)));
                continue;
            }
            WorldState.ActuatorRuntime rt = state.actuators.get(d.deviceId());
            String key = rngKeys.get(d.deviceId());
            long ackAt = t + d.response().ackDelay();
            String previous = rt.commandResults.get(c.commandId);
            if ("ACKED".equals(previous)) {
                // 같은 commandId 재전송: 다시 적용하지 않고 같은 응답(드라이버 계약 "중복 실행 없음")
                out.add(new Emission.CommandAck(c.commandId, d.deviceId(), d.organizationId(), "ACKED", null, Instant.ofEpochMilli(ackAt)));
                out.add(new Emission.StateReported(d.deviceId(), d.organizationId(), rt.reported.version, rt.reported.reported(),
                        Instant.ofEpochMilli(ackAt)));
                continue;
            }
            int attempt = rt.commandAttempts.merge(c.commandId, 1, Integer::sum);
            boolean lost = SimRandom.bernoulli(d.response().failure() / 100.0, d.seedOr(config.seed()), key, "cmd-fail",
                    c.commandId, attempt);
            if (lost) {
                rt.commandResults.put(c.commandId, "LOST");
                out.add(new Emission.Note(Instant.ofEpochMilli(t), "COMMAND", d.name() + " 명령 응답 없음(실패 확률): " + c.capability));
                trim(rt);
                continue;
            }
            ActuatorState next;
            try {
                next = CommandInterpreter.apply(d.type().key(), d.properties(), rt.reported, c.capability, c.command, c.args);
            } catch (BusinessException e) {
                rt.commandResults.put(c.commandId, "FAILED");
                out.add(new Emission.CommandAck(c.commandId, d.deviceId(), d.organizationId(), "FAILED", "INVALID_COMMAND",
                        Instant.ofEpochMilli(ackAt)));
                trim(rt);
                continue;
            }
            next.version = rt.reported.version + 1;
            rt.reported = next;
            rt.pendingEffects.add(new WorldState.PendingEffect(t + d.reactionDelaySec() * 1000L, next.copy()));
            rt.controlCount++;
            state.stats.controlCount++;
            rt.commandResults.put(c.commandId, "ACKED");
            trim(rt);
            out.add(new Emission.CommandAck(c.commandId, d.deviceId(), d.organizationId(), "ACKED", null, Instant.ofEpochMilli(ackAt)));
            out.add(new Emission.StateReported(d.deviceId(), d.organizationId(), next.version, next.reported(),
                    Instant.ofEpochMilli(ackAt)));
            out.add(new Emission.Note(Instant.ofEpochMilli(t), "COMMAND", d.name() + " " + c.capability + "." + c.command + " " + c.args
                    + (c.source == null ? "" : " (" + c.source + ")")));
        }
    }

    private static void trim(WorldState.ActuatorRuntime rt) {
        while (rt.commandResults.size() > 500) {
            String first = rt.commandResults.firstKey();
            rt.commandResults.remove(first);
            rt.commandAttempts.remove(first);
        }
    }

    private void stepPhysics(long startMs, long endMs) {
        Instant mid = Instant.ofEpochMilli((startMs + endMs) / 2);
        for (WorldSpace space : config.spaces()) {
            ActuatorEffects effects = new ActuatorEffects();
            for (WorldDevice d : devices) {
                if (d.actuator() && d.spaceId() == space.spaceId()) {
                    ActuatorPhysics.addEffects(d.type().key(), d.properties(), state.actuators.get(d.deviceId()).effective, effects);
                }
            }
            Outdoor outdoor = config.outdoor().at(mid, config.zone(), sun, space.physics().outdoorCo2Ppm());
            SpaceState s = state.spaces.get(space.spaceId());
            lastHvac.put(space.spaceId(), PhysicsModel.step(s, space.physics(), outdoor, effects, config.tickSec()));
        }
    }

    private void accumulateEnergy(long dtMs) {
        for (WorldDevice d : devices) {
            if (!d.actuator()) {
                continue;
            }
            WorldState.ActuatorRuntime rt = state.actuators.get(d.deviceId());
            double w = ActuatorPhysics.powerW(d.type().key(), d.properties(), rt.effective, lastHvac.get(d.spaceId()));
            rt.lastPowerW = w;
            double kwh = w * dtMs / 3_600_000.0 / 1000.0;
            rt.energyKwh += kwh;
            state.stats.energyKwh += kwh;
        }
    }

    private void recordHistory(long endMs) {
        for (Map.Entry<Long, SpaceState> e : state.spaces.entrySet()) {
            ArrayList<WorldState.SpaceSample> h = state.history.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            h.add(new WorldState.SpaceSample(endMs, e.getValue().copy()));
            while (h.size() > HISTORY_TICKS) {
                h.remove(0);
            }
        }
    }

    private void updateStats(long dtMs, long endMs) {
        for (SpaceState s : state.spaces.values()) {
            boolean ok = s.temperature >= COMFORT_MIN && s.temperature <= COMFORT_MAX && s.co2 <= COMFORT_CO2_MAX;
            if (ok) {
                state.stats.comfortSec += dtMs / 1000;
            } else {
                state.stats.outOfTargetSec += dtMs / 1000;
            }
        }
        for (Expectation x : config.expectations()) {
            WorldState.ExpectationProgress p = state.expectations.get(x.id());
            if (p == null || !"PENDING".equals(p.state)) {
                continue;
            }
            boolean beforeDeadline = x.deadline() == null || endMs <= x.deadline().toEpochMilli();
            switch (x.kind()) {
                case "DEVICE_STATE_REACHED" -> {
                    Long deviceId = longOf(x.target().get("deviceId"));
                    WorldState.ActuatorRuntime rt = deviceId == null ? null : state.actuators.get(deviceId);
                    if (rt != null && matches(rt.reported, x.condition())) {
                        p.state = beforeDeadline ? "PASSED" : "FAILED";
                        p.atMs = endMs;
                    } else if (!beforeDeadline) {
                        p.state = "FAILED";
                    }
                }
                case "METRIC_RANGE_RATIO" -> {
                    Long spaceId = longOf(x.target().get("spaceId"));
                    SpaceState s = spaceId == null ? null : state.spaces.get(spaceId);
                    if (s != null && beforeDeadline) {
                        double v = s.metric(String.valueOf(x.target().get("metric")));
                        p.total++;
                        if (v >= number(x.condition().get("min"), Double.NEGATIVE_INFINITY)
                                && v <= number(x.condition().get("max"), Double.POSITIVE_INFINITY)) {
                            p.inRange++;
                        }
                        p.value = p.total == 0 ? null : p.inRange / p.total;
                    }
                }
                case "CONTROL_COUNT_MAX" -> {
                    Long deviceId = longOf(x.target().get("deviceId"));
                    WorldState.ActuatorRuntime rt = deviceId == null ? null : state.actuators.get(deviceId);
                    long count = rt == null ? 0 : rt.controlCount;
                    p.value = (double) count;
                    if (count > number(x.condition().get("max"), Double.POSITIVE_INFINITY)) {
                        p.state = "FAILED";
                        p.atMs = endMs;
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * 실행 끝(또는 정지)에서 남은 기대 결과를 판정한다(BR-SIM-20). 정지된 실행은 끝난 구간까지만 판정하고, 기한이 아직 오지 않은
     * 상태 도달 항목은 SKIPPED.
     */
    public void finalizeExpectations(boolean partial) {
        long nowMs = simNow().toEpochMilli();
        for (Expectation x : config.expectations()) {
            WorldState.ExpectationProgress p = state.expectations.get(x.id());
            if (p == null || !"PENDING".equals(p.state)) {
                continue;
            }
            boolean deadlinePassed = x.deadline() == null ? !partial : nowMs >= x.deadline().toEpochMilli();
            switch (x.kind()) {
                case "DEVICE_STATE_REACHED" -> p.state = deadlinePassed ? "FAILED" : "SKIPPED";
                case "METRIC_RANGE_RATIO" -> {
                    double ratio = p.total == 0 ? 0 : p.inRange / p.total;
                    p.value = ratio;
                    p.state = p.total == 0 ? "SKIPPED" : ratio >= number(x.condition().get("ratio"), 1.0) ? "PASSED" : "FAILED";
                }
                case "CONTROL_COUNT_MAX" -> p.state = "PASSED";
                default -> p.state = "SKIPPED";
            }
        }
    }

    private static boolean matches(ActuatorState s, Map<String, Object> condition) {
        if (condition.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> c : condition.entrySet()) {
            if ("power".equals(c.getKey())) {
                boolean want = "ON".equalsIgnoreCase(String.valueOf(c.getValue())) || Boolean.TRUE.equals(c.getValue());
                if (s.on() != want) {
                    return false;
                }
            } else if (c.getValue() instanceof Map<?, ?> attrs) {
                for (Map.Entry<?, ?> a : attrs.entrySet()) {
                    Object actual = s.get(c.getKey(), String.valueOf(a.getKey()));
                    if (!sameValue(actual, a.getValue())) {
                        return false;
                    }
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean sameValue(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return Math.abs(a.doubleValue() - b.doubleValue()) < 1e-9;
        }
        return actual != null && String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    // ───────────────────────────── 센서 보고 ─────────────────────────────

    private Map<String, MetricSource> sources(WorldDevice d) {
        Map<String, MetricSource> m = new LinkedHashMap<>();
        for (MetricDef md : d.type().metrics()) {
            MetricSource override = d.metricSources().get(md.key());
            if (override != null) {
                m.put(md.key(), override);
            } else {
                m.put(md.key(), new MetricSource(md.defaultSource(), md.generator()));
            }
        }
        return m;
    }

    private void reportSensor(WorldDevice d, long endMs, Instant realNow, List<Emission> out, List<Reading> readings) {
        WorldState.SensorRuntime rt = state.sensors.get(d.deviceId());
        if (d.type().reportOnChange()) {
            double now = changeValue(d, rt, endMs - 1);
            if (rt.lastChangeValue == null || rt.lastChangeValue != now) {
                emitSensorReport(d, rt, endMs - 1, realNow, out, readings);
                rt.nextReportAtMs = endMs + (long) d.property("heartbeatSec", 3600) * 1000;
            }
        }
        int guard = 0;
        while (rt.nextReportAtMs < endMs && guard++ < 10_000) {
            long r = rt.nextReportAtMs;
            if (BatteryModel.depleted(rt.battery) && hasBattery(d)) {
                rt.nextReportAtMs = Long.MAX_VALUE;   // 배터리 0이면 보고 중단(TC-SIM-025)
                break;
            }
            emitSensorReport(d, rt, r, realNow, out, readings);
            long interval = d.type().reportOnChange() ? (long) d.property("heartbeatSec", 3600) * 1000
                    : ReportScheduler.nextIntervalMs(d.reportIntervalSec(), d.jitterPct(), d.seedOr(config.seed()),
                    rngKeys.get(d.deviceId()), rt.reportIndex - 1);
            rt.nextReportAtMs = r + interval;
        }
    }

    /** 변화 보고 센서(문)의 지금 값: 첫 측정 항목을 그 출처(생성기 또는 공간 물리)로 읽는다 */
    private double changeValue(WorldDevice d, WorldState.SensorRuntime rt, long atMs) {
        MetricDef md = d.type().metrics().get(0);
        MetricSource src = sources(d).get(md.key());
        if (src != null && src.generator() != null && !GeneratorSpec.RANDOM_WALK.equals(src.generator().kind())) {
            GeneratorState gs = rt.generators.computeIfAbsent(md.key(), x -> new GeneratorState());
            return Generators.sample(src.generator(), gs, Instant.ofEpochMilli(atMs), config.zone(), d.seedOr(config.seed()),
                    rngKeys.get(d.deviceId()), md.key(), rt.reportIndex);
        }
        SpaceState s = state.spaces.get(d.spaceId());
        double v = s == null ? 0 : s.metric(md.key());
        return Double.isNaN(v) ? 0 : v;
    }

    private static boolean hasBattery(WorldDevice d) {
        return d.type().metric("battery").isPresent();
    }

    private void emitSensorReport(WorldDevice d, WorldState.SensorRuntime rt, long r, Instant realNow, List<Emission> out,
                                  List<Reading> readings) {
        String key = rngKeys.get(d.deviceId());
        long seed = d.seedOr(config.seed());
        long index = rt.reportIndex++;
        rt.frameCounter++;
        Instant at = Instant.ofEpochMilli(r);
        List<FaultSpec> active = activeFaults(d, at);
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        Map<String, MetricSource> sources = sources(d);
        for (MetricDef md : d.type().metrics()) {
            double v = rawValue(d, rt, md, sources.get(md.key()), at, index, seed, key);
            if (Double.isNaN(v)) {
                continue;
            }
            if (!"battery".equals(md.key())) {
                v = applySensorFaults(md, v, active, rt, at, index, seed, key);
            }
            values.put(md.key(), v);
            if (md.unit() != null) {
                units.put(md.key(), md.unit());
            }
        }
        // 배터리(보고마다 감소)
        if (hasBattery(d)) {
            double drain = d.batteryDrainPerReport() != null ? d.batteryDrainPerReport()
                    : BatteryModel.drainPerReport(d.property("batteryLifeYears", 0), d.reportIntervalSec());
            rt.battery = BatteryModel.afterReport(rt.battery, drain);
        }
        if (d.type().reportOnChange()) {
            rt.lastChangeValue = values.getOrDefault(d.type().metrics().get(0).key(), 0.0);
        }
        if (dropped(active, at, index, seed, key)) {
            return;
        }
        values.forEach((k, v) -> rt.lastValues.put(k, v));
        values.forEach((k, v) -> readings.add(new Reading(key, k, at, v)));
        emitUplink(d, rt.frameCounter, at, values, units, active, realNow, out);
    }

    private double rawValue(WorldDevice d, WorldState.SensorRuntime rt, MetricDef md, MetricSource src, Instant at, long index,
                            long seed, String key) {
        String k = md.key();
        double v;
        String source = src == null ? md.defaultSource() : src.source();
        if (MetricDef.GENERATOR.equals(source) && src != null && src.generator() != null) {
            GeneratorState gs = rt.generators.computeIfAbsent(k, x -> new GeneratorState());
            v = Generators.sample(src.generator(), gs, at, config.zone(), seed, key, k, index);
            return round(clamp(v, md), md.resolution());
        }
        if (MetricDef.DERIVED.equals(source) || (MetricDef.GENERATOR.equals(source) && (src == null || src.generator() == null))) {
            v = derived(d, rt, k, at, index, seed, key);
            return Double.isNaN(v) ? v : round(clamp(v, md), md.resolution());
        }
        SpaceState sample = sampleAt(d.spaceId(), at.toEpochMilli() - (long) (d.property("responseDelaySec", 0) * 1000));
        if (sample == null) {
            return Double.NaN;
        }
        if ("occupancy".equals(k) && BuiltinCatalog.PIR_SENSOR.equals(d.type().key())) {
            return pirPresence(d, rt, sample, at, index, seed, key);
        }
        v = sample.metric(k);
        if (Double.isNaN(v)) {
            return v;
        }
        if ("LAImax".equals(k)) {
            v += 2 * Math.abs(SimRandom.gaussian(seed, key, k, "peak", index));
        }
        double sigma = sigma(d, k);
        if (sigma > 0) {
            v += sigma * SimRandom.gaussian(seed, key, k, "noise", index);
        }
        double drift = d.property(k + "DriftPerYear", 0);
        if (drift != 0) {
            v += drift * (at.toEpochMilli() - config.simStart().toEpochMilli()) / (double) YEAR_MS;
        }
        if (k.startsWith("pm") && d.properties().containsKey("humidityFactor")) {
            double rh = sample.relativeHumidity();
            if (rh > 70) {
                v *= 1 + d.property("humidityFactor", 0) * (rh - 70) / 30.0;
            }
        }
        if ("illumination".equals(k) && d.flag("windowSide")) {
            v *= 1.3;
        }
        return round(clamp(v, md), md.resolution());
    }

    private double sigma(WorldDevice d, String metric) {
        Object acc = d.properties().get(metric + "Accuracy");
        if (acc instanceof Number n) {
            return n.doubleValue() / 2.0;
        }
        WorldSpace s = spaces.get(d.spaceId());
        Double std = s == null ? null : s.physics().noiseStd().get(metric);
        return std == null ? 0 : std;
    }

    private double pirPresence(WorldDevice d, WorldState.SensorRuntime rt, SpaceState sample, Instant at, long index, long seed,
                               String key) {
        boolean detected = sample.occupancy > 0
                ? SimRandom.bernoulli(d.property("detectionProbability", 95) / 100.0, seed, key, "pir", index)
                : SimRandom.bernoulli(d.property("falsePositivePct", 1) / 100.0, seed, key, "pir-false", index);
        if (detected) {
            rt.lastDetectionMs = at.toEpochMilli();
        }
        return at.toEpochMilli() - rt.lastDetectionMs <= (long) (d.property("holdTimeSec", 300) * 1000) ? 1 : 0;
    }

    private double derived(WorldDevice d, WorldState.SensorRuntime rt, String k, Instant at, long index, long seed, String key) {
        switch (k) {
            case "battery":
                return rt.battery;
            case "activity": {
                SpaceState s = sampleAt(d.spaceId(), at.toEpochMilli());
                double lambda = s == null ? 0 : s.occupancy * 0.5 * d.reportIntervalSec() / 60.0;
                return poisson(lambda, SimRandom.stream(seed, key, "activity", index));
            }
            case "power", "energy": {
                double sum = 0;
                for (WorldDevice a : devices) {
                    if (a.actuator() && a.spaceId() == d.spaceId()) {
                        WorldState.ActuatorRuntime art = state.actuators.get(a.deviceId());
                        sum += "power".equals(k) ? art.lastPowerW : art.energyKwh;
                    }
                }
                return sum;
            }
            default:
                return Double.NaN;
        }
    }

    static double poisson(double lambda, SplittableRandom r) {
        if (lambda <= 0) {
            return 0;
        }
        if (lambda > 50) {
            return Math.max(0, Math.round(lambda + Math.sqrt(lambda) * SimRandom.gaussian(r)));
        }
        double l = Math.exp(-lambda);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l && k < 1000);
        return k - 1;
    }

    private SpaceState sampleAt(long spaceId, long atMs) {
        List<WorldState.SpaceSample> h = state.history.get(spaceId);
        if (h == null || h.isEmpty()) {
            return state.spaces.get(spaceId);
        }
        WorldState.SpaceSample best = null;
        for (WorldState.SpaceSample s : h) {
            if (s.atMs <= atMs) {
                best = s;
            } else {
                break;
            }
        }
        if (best == null) {
            best = h.get(0);
        }
        return best.state;
    }

    private List<FaultSpec> activeFaults(WorldDevice d, Instant at) {
        List<FaultSpec> list = new ArrayList<>();
        String id = Long.toString(d.deviceId());
        for (FaultSpec f : state.faults) {
            boolean target = FaultSpec.DEVICE.equals(f.targetType()) ? id.equals(f.targetId())
                    : d.gatewayEui() != null && d.gatewayEui().equalsIgnoreCase(f.targetId());
            if (target && f.activeAt(at)) {
                list.add(f);
            }
        }
        return list;
    }

    private double applySensorFaults(MetricDef md, double v, List<FaultSpec> active, WorldState.SensorRuntime rt, Instant at,
                                     long index, long seed, String key) {
        double out = v;
        for (FaultSpec f : active) {
            Object metric = f.params().get("metric");
            if (metric != null && !metric.equals(md.key())) {
                continue;
            }
            WorldState.FaultRuntime fr = state.faultRuntime.computeIfAbsent(f.faultId(), k -> new WorldState.FaultRuntime());
            switch (f.kind()) {
                case STUCK -> {
                    Double fixed = f.params().get("value") instanceof Number n ? n.doubleValue() : null;
                    Double stuck = fr.stuck.get(md.key());
                    if (stuck == null) {
                        stuck = fixed != null ? fixed : rt.lastValues.getOrDefault(md.key(), out);
                        fr.stuck.put(md.key(), stuck);
                    }
                    out = stuck;
                }
                case SPIKE -> {
                    // 같은 보고 안의 여러 항목은 한 번으로 센다(spike +20 ×3 → 정확히 3개 지점)
                    long t = at.toEpochMilli();
                    if (fr.lastSpikeAtMs == t || fr.spikesUsed < (int) f.number("count", 1)) {
                        if (fr.lastSpikeAtMs != t) {
                            fr.spikesUsed++;
                            fr.lastSpikeAtMs = t;
                        }
                        out = round(out + f.number("magnitude", 10), md.resolution());
                    }
                }
                case DRIFT -> out = round(out + f.number("perHour", 1)
                        * (at.toEpochMilli() - f.simFrom().toEpochMilli()) / 3_600_000.0, md.resolution());
                case OUT_OF_RANGE -> {
                    double value = f.params().get("value") instanceof Number n ? n.doubleValue()
                            : md.validMax() != null ? md.validMax() * 2 + 100 : 99_999;
                    out = value;
                }
                default -> {
                }
            }
        }
        return out;
    }

    private boolean dropped(List<FaultSpec> active, Instant at, long index, long seed, String key) {
        for (FaultSpec f : active) {
            switch (f.kind()) {
                case GATEWAY_DOWN -> {
                    return true;
                }
                case DROPOUT -> {
                    if (SimRandom.bernoulli(f.number("ratio", 1.0), seed, key, "dropout", faultKey(f), index)) {
                        return true;
                    }
                }
                case INTERMITTENT -> {
                    long on = (long) (f.number("onSec", 60) * 1000);
                    long off = (long) (f.number("offSec", 60) * 1000);
                    long phase = Math.floorMod(at.toEpochMilli() - f.simFrom().toEpochMilli(), on + off);
                    if (phase >= on) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    // ───────────────────────────── 장비 보고 ─────────────────────────────

    private void reportActuator(WorldDevice d, long endMs, Instant realNow, List<Emission> out, List<Reading> readings) {
        WorldState.ActuatorRuntime rt = state.actuators.get(d.deviceId());
        String key = rngKeys.get(d.deviceId());
        int guard = 0;
        while (rt.nextReportAtMs < endMs && guard++ < 10_000) {
            long r = rt.nextReportAtMs;
            Instant at = Instant.ofEpochMilli(r);
            rt.frameCounter++;
            long index = rt.reportIndex++;
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("power", round(rt.lastPowerW, 0.1));
            values.put("energy", round(rt.energyKwh, 0.001));
            Map<String, String> units = new LinkedHashMap<>();
            units.put("power", "W");
            units.put("energy", "kWh");
            List<FaultSpec> active = activeFaults(d, at);
            if (!dropped(active, at, index, d.seedOr(config.seed()), key)) {
                values.forEach((k, v) -> readings.add(new Reading(key, k, at, v)));
                emitUplink(d, rt.frameCounter, at, values, units, active, realNow, out);
            }
            rt.nextReportAtMs = r + ReportScheduler.nextIntervalMs(d.reportIntervalSec(), d.jitterPct(), d.seedOr(config.seed()),
                    key, index);
        }
    }

    // ───────────────────────────── 전송·인프라 장애 ─────────────────────────────

    private void emitUplink(WorldDevice d, long fCnt, Instant measured, Map<String, Double> values, Map<String, String> units,
                            List<FaultSpec> active, Instant realNow, List<Emission> out) {
        String key = rngKeys.get(d.deviceId());
        long seed = d.seedOr(config.seed());
        SplittableRandom radio = SimRandom.stream(seed, key, "radio");
        double rssiBase = -110 + radio.nextDouble() * 70;
        double snrBase = -5 + radio.nextDouble() * 17;
        double rssi = Math.max(-120, Math.min(-30, rssiBase + 3 * SimRandom.gaussian(seed, key, "rssi", fCnt)));
        double snr = Math.max(-20, Math.min(15, snrBase + 1.5 * SimRandom.gaussian(seed, key, "snr", fCnt)));
        boolean wall = config.timestampPolicy() == TimestampPolicy.WALL_CLOCK;
        Instant measuredAt = wall ? realNow : measured;
        Map<String, String> tags = new TreeMap<>();
        tags.put("simulator", "data2flow-simulator");
        tags.put("spaceId", Long.toString(d.spaceId()));
        tags.put("virtual", "true");
        UUID dedupId = SimRandom.uuid(seed, runKey, key, "uplink", fCnt);
        UplinkFrame frame = new UplinkFrame(d.organizationId(), d.sourceId(), d.externalId(), d.name(), d.profileName(), measuredAt,
                values, units, fCnt, d.gatewayEui() == null ? defaultGateway(d.organizationId()) : d.gatewayEui(), rssi, snr,
                dedupId, tags);
        List<EncodedUplink> encoded = PayloadEncoder.encode(d.payloadFormat(), frame, PayloadEncoder.bucketWidth(d.reportIntervalSec()));
        int copies = 1;
        long delayMs = 0;
        FaultSpec reorder = null;
        boolean malformed = false;
        for (FaultSpec f : active) {
            switch (f.kind()) {
                case DUPLICATE -> copies = Math.max(copies, (int) f.number("factor", 2));
                case DELAY -> delayMs = Math.max(delayMs, (long) (f.number("delaySec", 60) * 1000));
                case REORDER -> reorder = f;
                case MALFORMED -> malformed |= SimRandom.bernoulli(f.number("ratio", 0.1), seed, key, "malformed", faultKey(f), fCnt);
                default -> {
                }
            }
        }
        int part = 0;
        for (EncodedUplink e : encoded) {
            byte[] payload = malformed ? PayloadEncoder.corrupt(e.payload()) : e.payload();
            String dedupKey = malformed ? net.java21.data2flow.contracts.messaging.DedupKeys.content(d.sourceId(), e.topic(), payload)
                    : e.dedupKey();
            for (int copy = 0; copy < copies; copy++) {
                WorldState.PendingUplink p = new WorldState.PendingUplink();
                p.deviceId = d.deviceId();
                p.sourceId = d.sourceId();
                p.topic = e.topic();
                p.payload = payload;
                p.dedupKey = dedupKey;
                p.messageId = SimRandom.uuid(seed, runKey, key, "message", fCnt, part, copy).toString();
                p.measuredAtMs = measuredAt.toEpochMilli();
                p.sendAtMs = measured.toEpochMilli() + delayMs;
                if (reorder != null) {
                    WorldState.FaultRuntime fr = state.faultRuntime.computeIfAbsent(reorder.faultId(), k -> new WorldState.FaultRuntime());
                    if (fr.reorderBuffer.isEmpty()) {
                        fr.reorderStartMs = measured.toEpochMilli();
                    }
                    fr.reorderBuffer.add(p);
                } else if (delayMs > 0) {
                    state.pending.add(p);
                } else {
                    out.add(toEmission(p, realNow));
                    state.uplinkCount++;
                }
            }
            part++;
        }
    }

    private void flushPending(long endMs, List<Emission> out) {
        Instant realNow = null;
        List<WorldState.PendingUplink> due = new ArrayList<>();
        for (var it = state.pending.iterator(); it.hasNext(); ) {
            WorldState.PendingUplink p = it.next();
            if (p.sendAtMs < endMs) {
                due.add(p);
                it.remove();
            }
        }
        for (FaultSpec f : state.faults) {
            if (f.kind() != FaultKind.REORDER) {
                continue;
            }
            WorldState.FaultRuntime fr = state.faultRuntime.get(f.faultId());
            if (fr == null || fr.reorderBuffer.isEmpty()) {
                continue;
            }
            long window = (long) (f.number("windowSec", 60) * 1000);
            boolean over = f.simTo() != null && f.simTo().toEpochMilli() < endMs;
            if (endMs - fr.reorderStartMs >= window || over) {
                // 붙잡아 둔 순서를 뒤집어 보낸다(측정 시각은 그대로)
                List<WorldState.PendingUplink> buf = new ArrayList<>(fr.reorderBuffer);
                java.util.Collections.reverse(buf);
                for (WorldState.PendingUplink p : buf) {
                    p.sendAtMs = endMs - 1;
                    due.add(p);
                }
                fr.reorderBuffer.clear();
            }
        }
        for (WorldState.PendingUplink p : due) {
            out.add(toEmission(p, realNow));
            state.uplinkCount++;
        }
    }

    private Emission.Uplink toEmission(WorldState.PendingUplink p, Instant realNow) {
        boolean wall = config.timestampPolicy() == TimestampPolicy.WALL_CLOCK && realNow != null;
        Instant received = wall ? realNow : Instant.ofEpochMilli(p.sendAtMs);
        return new Emission.Uplink(p.deviceId, config.organizationId(), p.sourceId, p.topic, p.payload, p.dedupKey,
                UUID.fromString(p.messageId), Instant.ofEpochMilli(p.measuredAtMs), received);
    }

    /** 장애 난수 키: DB ID가 아니라 내용(종류·대상·시작)으로 정해 실행마다 같다 */
    static String faultKey(FaultSpec f) {
        return f.kind() + ":" + f.targetType() + ":" + f.targetId() + ":" + f.simFrom().toEpochMilli();
    }

    /** 조직 기본 가상 게이트웨이 EUI */
    public static String defaultGateway(long organizationId) {
        return String.format("5a1d00ff%08x", organizationId & 0xffffffffL);
    }

    // ───────────────────────────── 해시 ─────────────────────────────

    private void appendDigest(List<Reading> tickReadings) {
        if (tickReadings.isEmpty()) {
            return;
        }
        tickReadings.sort(Comparator.comparing(Reading::device).thenComparing(Reading::simTime).thenComparing(Reading::metric));
        MessageDigest md = sha256();
        md.update(state.digest.getBytes(StandardCharsets.UTF_8));
        for (Reading r : tickReadings) {
            md.update((r.device() + '|' + r.metric() + '|' + r.simTime().toEpochMilli() + '|' + r.value() + '\n')
                    .getBytes(StandardCharsets.UTF_8));
            if (recorder != null) {
                recorder.add(r);
            }
        }
        state.readingCount += tickReadings.size();
        state.digest = HexFormat.of().formatHex(md.digest());
    }

    /** 생성 값 목록을 (기기, 측정 키, 시각, 값)으로 정렬해 SHA-256(test-plan "결정성 판정") */
    public static String sortedHash(List<Reading> readings) {
        List<Reading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(Reading::device).thenComparing(Reading::metric).thenComparing(Reading::simTime)
                .thenComparingDouble(Reading::value));
        MessageDigest md = sha256();
        for (Reading r : sorted) {
            md.update((r.device() + '|' + r.metric() + '|' + r.simTime().toEpochMilli() + '|' + r.value() + '\n')
                    .getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────────── 유틸 ─────────────────────────────

    static double round(double v, double resolution) {
        if (resolution <= 0 || Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return BigDecimal.valueOf(Math.round(v / resolution)).multiply(BigDecimal.valueOf(resolution)).doubleValue();
    }

    private static double clamp(double v, MetricDef md) {
        double out = v;
        if (md.validMin() != null) {
            out = Math.max(md.validMin(), out);
        }
        if (md.validMax() != null) {
            out = Math.min(md.validMax(), out);
        }
        return out;
    }

    private static Long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v == null ? null : parseId(String.valueOf(v));
    }

    private static double number(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
