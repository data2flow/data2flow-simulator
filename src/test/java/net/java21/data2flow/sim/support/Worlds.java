package net.java21.data2flow.sim.support;

import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.device.domain.ResponseSettings;
import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.TimestampPolicy;
import net.java21.data2flow.sim.engine.WorldConfig;
import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.engine.WorldSpace;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.physics.domain.SpacePreset;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 시뮬레이션 엔진 시험용 구성 빌더 */
public final class Worlds {

    public static final Instant START = Instant.parse("2026-08-10T00:00:00Z");   // 서울 09:00
    public static final long ORG = 1;
    public static final long SOURCE = 3;
    public static final long SPACE = 100;

    private long seed = 42;
    private Long runId = 7L;
    private Instant start = START;
    private int tickSec = 10;
    private long totalTicks = 360;
    private TimestampPolicy policy = TimestampPolicy.SIMULATED;
    private OutdoorSpec outdoor = OutdoorSpec.constant(25, 50);
    private final List<WorldSpace> spaces = new ArrayList<>();
    private final List<WorldDevice> devices = new ArrayList<>();
    private final List<ScenarioEvent> events = new ArrayList<>();
    private final List<FaultSpec> faults = new ArrayList<>();
    private final List<Expectation> expectations = new ArrayList<>();

    public static Worlds classroom() {
        Worlds w = new Worlds();
        w.spaces.add(new WorldSpace(SPACE, "데모 강의실", SpacePhysics.preset(SpacePreset.CLASSROOM), false));
        return w;
    }

    public Worlds seed(long v) { this.seed = v; return this; }
    public Worlds runId(Long v) { this.runId = v; return this; }
    public Worlds start(Instant v) { this.start = v; return this; }
    public Worlds tickSec(int v) { this.tickSec = v; return this; }
    public Worlds hours(double h) { this.totalTicks = Math.round(h * 3600 / tickSec); return this; }
    public Worlds policy(TimestampPolicy v) { this.policy = v; return this; }
    public Worlds outdoor(OutdoorSpec v) { this.outdoor = v; return this; }
    public Worlds space(WorldSpace s) { spaces.add(s); return this; }
    public Worlds event(ScenarioEvent e) { events.add(e); return this; }
    public Worlds fault(FaultSpec f) { faults.add(f); return this; }
    public Worlds expectation(Expectation x) { expectations.add(x); return this; }
    public Worlds device(WorldDevice d) { devices.add(d); return this; }

    public WorldConfig config() {
        return new WorldConfig(runId, ORG, seed, start, tickSec, totalTicks, policy, ZoneId.of("Asia/Seoul"), outdoor, spaces,
                devices, events, faults, expectations);
    }

    public SimulationWorld world() {
        return SimulationWorld.start(config(), true);
    }

    /** 끝까지 돌리고 모든 방출을 모은다 */
    public static List<Emission> runToEnd(SimulationWorld w) {
        List<Emission> all = new ArrayList<>();
        while (!w.finished()) {
            all.addAll(w.step(START));
        }
        return all;
    }

    public static List<Emission.Uplink> uplinks(List<Emission> emissions) {
        return emissions.stream().filter(e -> e instanceof Emission.Uplink).map(e -> (Emission.Uplink) e).toList();
    }

    /** 기기 빌더 */
    public static Dev dev(long id, String name, String typeKey) {
        return new Dev(id, name, typeKey);
    }

    public static final class Dev {
        private final long id;
        private final String name;
        private final DeviceTypeDef type;
        private long space = SPACE;
        private Integer interval;
        private double jitter;
        private PayloadFormat format = PayloadFormat.CHIRPSTACK_V4;
        private final Map<String, Object> overrides = new LinkedHashMap<>();
        private final Map<String, MetricSource> sources = new LinkedHashMap<>();
        private Double drain;
        private Double battery;
        private ResponseSettings response = ResponseSettings.DEFAULT;
        private String gateway = "5a1d00ff00000001";
        private Long seed;

        Dev(long id, String name, String typeKey) {
            this.id = id;
            this.name = name;
            this.type = BuiltinCatalog.type(typeKey).orElseThrow();
        }

        public Dev space(long v) { this.space = v; return this; }
        public Dev interval(int v) { this.interval = v; return this; }
        public Dev jitter(double v) { this.jitter = v; return this; }
        public Dev format(PayloadFormat v) { this.format = v; return this; }
        public Dev prop(String k, Object v) { overrides.put(k, v); return this; }
        public Dev source(String metric, MetricSource s) { sources.put(metric, s); return this; }
        public Dev drain(double v) { this.drain = v; return this; }
        public Dev battery(double v) { this.battery = v; return this; }
        public Dev response(ResponseSettings v) { this.response = v; return this; }
        public Dev gateway(String v) { this.gateway = v; return this; }
        public Dev seed(long v) { this.seed = v; return this; }

        public WorldDevice build() {
            return new WorldDevice(id, ORG, name, String.format("5a1d%012x", id), SOURCE, space, type,
                    PropertyResolver.effective(type, Map.of(), overrides), sources,
                    interval == null ? type.defaultReportIntervalSec() : interval, jitter, drain, format, gateway, response, seed,
                    0, battery, null);
        }
    }
}
