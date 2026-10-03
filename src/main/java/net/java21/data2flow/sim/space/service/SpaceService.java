package net.java21.data2flow.sim.space.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.actuator.domain.ActuatorPhysics;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.actuator.domain.CommandInterpreter;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.physics.domain.ActuatorEffects;
import net.java21.data2flow.sim.physics.domain.Outdoor;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.PhysicsModel;
import net.java21.data2flow.sim.physics.domain.SolarModel;
import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.physics.domain.SpacePreset;
import net.java21.data2flow.sim.physics.domain.SpaceState;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.run.service.SimulationExecutor;
import net.java21.data2flow.sim.run.service.WorldFactory;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;
import net.java21.data2flow.sim.sensor.domain.GeneratorState;
import net.java21.data2flow.sim.sensor.domain.Generators;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 가상 공간 물리 설정(SIM-01.01·01.02, API-SIM-10·11·24 의 simulator 쪽) */
@Service
public class SpaceService {

    public static final int PREVIEW_STEP_SEC = 60;
    public static final int PREVIEW_POINT_EVERY = 10;

    private final SpacePhysicsRepository spaces;
    private final SimDeviceRepository devices;
    private final RunRepository runs;
    private final WorldFactory factory;
    private final SimulationExecutor executor;
    private final Clock clock;

    public SpaceService(SpacePhysicsRepository spaces, SimDeviceRepository devices, RunRepository runs, WorldFactory factory,
                        SimulationExecutor executor, Clock clock) {
        this.spaces = spaces;
        this.devices = devices;
        this.runs = runs;
        this.factory = factory;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * 요청: 프리셋만 주면 프리셋 기본값, physics를 주면 그 값(빠진 항목은 프리셋 값).
     *
     * @param name    공간 이름(core 정본, 표시용)
     * @param preset  CLASSROOM·OFFICE·MEETING·CUSTOM
     * @param physics 물리 파라미터
     */
    public record SpaceRequest(String name, String preset, SpacePhysics physics) {
    }

    /** 공간 물리 설정을 만들거나 바꾼다(core 사가 2단계) */
    @Transactional
    public Map<String, Object> upsert(long organizationId, long spaceId, SpaceRequest req) {
        SpacePhysics p = resolve(req);
        List<String[]> v = p.violations();
        if (!v.isEmpty()) {
            List<FieldErrorDetail> errors = v.stream()
                    .map(x -> new FieldErrorDetail(x[0], SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(), "허용 범위: " + x[1])).toList();
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE, errors);
        }
        boolean created = spaces.upsert(organizationId, spaceId, p);
        executor.devicesChanged(organizationId, List.of());
        Map<String, Object> r = view(spaces.findById(organizationId, spaceId).orElseThrow());
        r.put("created", created);
        return r;
    }

    static SpacePhysics resolve(SpaceRequest req) {
        if (req.physics() != null) {
            SpacePhysics in = req.physics();
            SpacePreset preset = in.preset() != null ? in.preset() : parsePreset(req.preset());
            SpacePhysics base = SpacePhysics.preset(preset);
            return new SpacePhysics(preset, in.areaM2(), in.heightM(), in.uValue(), in.envelopeM2(), in.windowM2(),
                    in.windowOrientation() == null ? base.windowOrientation() : in.windowOrientation(), in.solarGainFactor(),
                    in.initialState() == null ? base.initialState() : in.initialState(), in.outdoorLinked(),
                    in.outdoorCo2Ppm() == 0 ? base.outdoorCo2Ppm() : in.outdoorCo2Ppm(),
                    in.perPerson() == null ? base.perPerson() : in.perPerson(),
                    in.backgroundNoiseDb() == 0 ? base.backgroundNoiseDb() : in.backgroundNoiseDb(),
                    in.noiseStd().isEmpty() ? base.noiseStd() : in.noiseStd());
        }
        return SpacePhysics.preset(parsePreset(req.preset()));
    }

    static SpacePreset parsePreset(String raw) {
        try {
            return raw == null ? SpacePreset.CLASSROOM : SpacePreset.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("preset", "Pattern", "CLASSROOM|OFFICE|MEETING|CUSTOM")));
        }
    }

    public Map<String, Object> get(long organizationId, long spaceId) {
        return view(spaces.findById(organizationId, spaceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND)));
    }

    public List<Map<String, Object>> list(long organizationId) {
        return spaces.findAll(organizationId).stream().map(this::view).toList();
    }

    Map<String, Object> view(SpacePhysicsRepository.SpaceRow s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spaceId", Long.toString(s.spaceId()));
        m.put("preset", s.physics().preset());
        m.put("physics", s.physics());
        m.put("sandbox", s.sandbox());
        m.put("deviceCount", devices.countBySpace(s.organizationId(), s.spaceId()));
        m.put("volumeM3", s.physics().volumeM3());
        m.put("current", executor.spaceState(s.organizationId(), s.spaceId()).map(SpaceService::current).orElse(null));
        m.put("version", s.version());
        m.put("updatedAt", s.updatedAt());
        return m;
    }

    static Map<String, Object> current(SpaceState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("temperature", Math.round(s.temperature * 10) / 10.0);
        m.put("humidity", Math.round(s.relativeHumidity() * 10) / 10.0);
        m.put("co2", Math.round(s.co2));
        m.put("pm2_5", Math.round(s.pm25 * 10) / 10.0);
        m.put("illumination", Math.round(s.illumination));
        m.put("noise", Math.round(s.noise * 10) / 10.0);
        m.put("occupancy", s.occupancy);
        return m;
    }

    /** 삭제: 실행 중 시나리오가 쓰면 SIM_SPACE_BUSY, 가상 기기가 남아 있으면 SIM_SPACE_BUSY */
    @Transactional
    public void delete(long organizationId, long spaceId) {
        spaces.findById(organizationId, spaceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if (runs.findBusySpaces(organizationId).contains(spaceId) || devices.countBySpace(organizationId, spaceId) > 0) {
            throw new BusinessException(SimErrorCode.SIM_SPACE_BUSY);
        }
        spaces.delete(organizationId, spaceId);
        executor.devicesChanged(organizationId, List.of());
    }

    /** 샌드박스 지정(API-SIM-24의 simulator 쪽 기록. ACT 차단은 core·action 몫) */
    @Transactional
    public Map<String, Object> sandbox(long organizationId, long spaceId, boolean sandbox) {
        if (spaces.setSandbox(organizationId, spaceId, sandbox) == 0) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        return Map.of("spaceId", Long.toString(spaceId), "sandbox", sandbox);
    }

    // ───────────── 미리 보기(API-SIM-11) ─────────────

    /**
     * @param spaceId   저장된 공간(또는 physics)
     * @param condition {occupancy, outdoorTemp, outdoorHumidity, actuators:{deviceId: {capability:{attr:value}}}}
     * @param generator 생성기 미리 보기
     * @param hours     1~48
     */
    public record PreviewRequest(Long spaceId, SpacePhysics physics, Map<String, Object> condition, GeneratorSpec generator,
                                 Integer hours) {
    }

    public Map<String, Object> preview(long organizationId, PreviewRequest req) {
        int hours = req.hours() == null ? 6 : req.hours();
        if (hours < 1 || hours > 48) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("hours", "Range", "1~48")));
        }
        Instant start = clock.instant();
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Map<String, List<Map<String, Object>>> series = new LinkedHashMap<>();
        if (req.generator() != null) {
            String problem = Generators.validate(req.generator());
            if (problem != null) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("generator." + problem,
                        "Invalid", "생성기 설정을 확인하세요")));
            }
            GeneratorState gs = new GeneratorState();
            List<Map<String, Object>> points = new ArrayList<>();
            for (int i = 0; i <= hours * 60; i += PREVIEW_POINT_EVERY) {
                Instant t = start.plusSeconds(i * 60L);
                Generators.tick(req.generator(), gs, 0, "preview", "value", i, t, PREVIEW_POINT_EVERY * 60.0);
                points.add(point(t, Generators.sample(req.generator(), gs, t, zone, 0, "preview", "value", i)));
            }
            series.put("value", points);
            return Map.of("series", series);
        }
        SpacePhysics physics = req.physics() != null ? resolve(new SpaceRequest(null, null, req.physics()))
                : spaces.findById(organizationId, req.spaceId() == null ? -1 : req.spaceId())
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND)).physics();
        Map<String, Object> c = req.condition() == null ? Map.of() : req.condition();
        double outdoorTemp = number(c.get("outdoorTemp"), 30);
        double outdoorHumidity = number(c.get("outdoorHumidity"), 60);
        OutdoorSpec outdoor = c.containsKey("outdoorTemp") ? OutdoorSpec.constant(outdoorTemp, outdoorHumidity)
                : OutdoorSpec.diurnal(outdoorTemp + 4, outdoorTemp - 4, 15, outdoorHumidity);
        ActuatorEffects effects = new ActuatorEffects();
        if (c.get("actuators") instanceof Map<?, ?> actuators) {
            for (Map.Entry<?, ?> e : actuators.entrySet()) {
                long deviceId = Long.parseLong(String.valueOf(e.getKey()));
                WorldDevice d = devices.findById(organizationId, deviceId).map(factory::device)
                        .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
                ActuatorState state = ActuatorState.initial(d.type().capabilities());
                if (e.getValue() instanceof Map<?, ?> caps) {
                    for (Map.Entry<?, ?> cap : caps.entrySet()) {
                        Map<String, Object> args = new LinkedHashMap<>();
                        ((Map<?, ?>) cap.getValue()).forEach((k, v) -> args.put(String.valueOf(k), v));
                        state = CommandInterpreter.apply(d.type().key(), d.properties(), state, String.valueOf(cap.getKey()), "set", args);
                    }
                }
                ActuatorPhysics.addEffects(d.type().key(), d.properties(), state, effects);
            }
        }
        SpaceState s = SpaceState.initial(physics);
        s.occupancy = (int) number(c.get("occupancy"), 0);
        SolarModel sun = SolarModel.seoul();
        for (String k : List.of("temperature", "humidity", "co2", "pm2_5", "illumination", "noise")) {
            series.put(k, new ArrayList<>());
        }
        for (int minute = 0; minute <= hours * 60; minute++) {
            Instant t = start.plusSeconds(minute * 60L);
            if (minute % PREVIEW_POINT_EVERY == 0) {
                series.get("temperature").add(point(t, round1(s.temperature)));
                series.get("humidity").add(point(t, round1(s.relativeHumidity())));
                series.get("co2").add(point(t, Math.round(s.co2)));
                series.get("pm2_5").add(point(t, round1(s.pm25)));
                series.get("illumination").add(point(t, Math.round(s.illumination)));
                series.get("noise").add(point(t, round1(s.noise)));
            }
            Outdoor o = outdoor.at(t, zone, sun, physics.outdoorCo2Ppm());
            PhysicsModel.step(s, physics, o, effects, PREVIEW_STEP_SEC);
        }
        return Map.of("series", series);
    }

    private static Map<String, Object> point(Instant t, double v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("v", v);
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double number(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
