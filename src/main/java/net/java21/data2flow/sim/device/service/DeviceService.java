package net.java21.data2flow.sim.device.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.KitDef;
import net.java21.data2flow.sim.catalog.domain.MetricDef;
import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import net.java21.data2flow.sim.catalog.domain.PropertyValidator;
import net.java21.data2flow.sim.catalog.repository.CatalogRepository;
import net.java21.data2flow.sim.catalog.repository.ProfileRepository;
import net.java21.data2flow.sim.catalog.service.CatalogService;
import net.java21.data2flow.sim.common.SimDirectory;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.device.domain.OutputPath;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.device.domain.ReportMode;
import net.java21.data2flow.sim.device.domain.ResponseSettings;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.run.service.SimulationExecutor;
import net.java21.data2flow.sim.sensor.domain.Generators;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 가상 기기 시뮬레이션 설정(SIM-02·03·09, API-SIM-05·06·07·09·33의 simulator 쪽). 기기 기준 정보는 core가 DEV에 {@code virtual=true}로 먼저
 * 만들고(사가 1단계), 그 ID로 여기를 부른다(2단계). 실패하면 core가 보상 삭제한다.
 */
@Service
public class DeviceService {

    /** 한 번에 배치할 수 있는 최대 대수(API-SIM-05 count 1~50) */
    public static final int MAX_BATCH = 50;

    private final SimDeviceRepository devices;
    private final SpacePhysicsRepository spaces;
    private final CatalogService catalog;
    private final ProfileRepository profiles;
    private final RunRepository runs;
    private final SimDirectory directory;
    private final SimulationExecutor executor;
    private final SimProperties properties;

    public DeviceService(SimDeviceRepository devices, SpacePhysicsRepository spaces, CatalogService catalog, ProfileRepository profiles,
                         RunRepository runs, SimDirectory directory, SimulationExecutor executor, SimProperties properties) {
        this.devices = devices;
        this.spaces = spaces;
        this.catalog = catalog;
        this.profiles = profiles;
        this.runs = runs;
        this.directory = directory;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * API-SIM-33 {@code POST /internal/sim/devices}. 문서 필드 {deviceId, typeId, profileId, spaceId, reportMode, overrides}에 더해
     * core 기준 정보 일부(name, externalId, sourceId)와 설정 초기값을 받는다(모두 선택).
     *
     * @param typeKey    typeId 대신 유형 키
     * @param externalId 수집 경로가 기기를 찾는 외부 ID(devEui). 없으면 {@code 5a1d} + 기기 ID 16진수 12자리
     * @param sourceId   SIM 데이터 소스. 없으면 배포 조직의 SIM 소스
     */
    public record CreateRequest(Long deviceId, Long typeId, String typeKey, Long profileId, Long spaceId, String reportMode,
                                Map<String, Object> overrides, String name, String externalId, Long sourceId,
                                Map<String, MetricSource> metricSources, Integer reportIntervalSec, Double jitterPct,
                                String payloadFormat, String outputPath, ResponseSettings response, Long seed, String gatewayEui) {
    }

    @Transactional
    public Map<String, Object> create(long organizationId, CreateRequest req) {
        return createAll(organizationId, List.of(req)).get(0);
    }

    /** 여러 대를 한 트랜잭션으로(API-SIM-05 count, 키트·프리셋). 한도를 넘으면 한 대도 만들지 않는다(TC-SIM-091) */
    @Transactional
    public List<Map<String, Object>> createAll(long organizationId, List<CreateRequest> requests) {
        if (requests.isEmpty() || requests.size() > MAX_BATCH * 10) {
            throw invalid("devices", "Size", "1~" + MAX_BATCH * 10 + "대");
        }
        long existing = devices.countByOrganization(organizationId);
        if (existing + requests.size() > properties.limits().devicesPerOrganization()) {
            throw new BusinessException(SimErrorCode.SIM_DEVICE_QUOTA_EXCEEDED);
        }
        List<Map<String, Object>> created = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (CreateRequest req : requests) {
            SimDeviceRepository.DeviceRow row = build(organizationId, req);
            try {
                devices.insert(row);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(CommonErrorCode.VERSION_CONFLICT,
                        List.of(new FieldErrorDetail("deviceId", "DUPLICATE", "이미 있는 가상 기기 또는 외부 ID입니다: " + row.deviceId())));
            }
            ids.add(row.deviceId());
            created.add(view(row));
        }
        executor.devicesChanged(organizationId, ids);
        return created;
    }

    private SimDeviceRepository.DeviceRow build(long organizationId, CreateRequest req) {
        if (req.deviceId() == null || req.deviceId() <= 0) {
            throw invalid("deviceId", "NotNull", "core 기기 ID가 필요합니다");
        }
        if (req.spaceId() == null) {
            throw invalid("spaceId", "NotNull", "가상 공간이 필요합니다");
        }
        spaces.findById(organizationId, req.spaceId()).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND,
                List.of(new FieldErrorDetail("spaceId", "NotFound", "가상 공간 물리 설정이 없습니다"))));
        CatalogRepository.TypeRow type = req.typeId() != null ? catalog.type(organizationId, req.typeId())
                : catalog.typeByKey(organizationId, req.typeKey() == null ? "" : req.typeKey());
        Map<String, Object> profileOverrides = Map.of();
        if (req.profileId() != null) {
            ProfileRepository.ProfileRow p = profiles.findById(organizationId, req.profileId())
                    .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
            if (p.typeId() != type.id()) {
                throw invalid("profileId", "TYPE_MISMATCH", "유형이 다른 프로필입니다");
            }
            profileOverrides = p.overrides();
        }
        Map<String, Object> overrides = PropertyResolver.merge(Map.of(), req.overrides());
        PropertyValidator.validate(type.def(), overrides, "overrides.");
        Map<String, MetricSource> sources = validateSources(type.def(), req.metricSources());
        OutputPath output = parse(req.outputPath(), OutputPath.class, OutputPath.INTERNAL, "outputPath");
        guardOutput(output);
        long sourceId = req.sourceId() != null ? req.sourceId() : directory.source(organizationId)
                .map(SimDirectory.SimSource::sourceId)
                .orElseThrow(() -> invalid("sourceId", "NotNull", "조직의 SIM 데이터 소스를 찾지 못했습니다"));
        PayloadFormat defaultFormat = directory.source(organizationId).map(SimDirectory.SimSource::payloadFormat)
                .filter(f -> f != null).orElse(type.def().defaultPayloadFormat());
        PayloadFormat format = parse(req.payloadFormat(), PayloadFormat.class, defaultFormat, "payloadFormat");
        int interval = req.reportIntervalSec() == null ? type.def().defaultReportIntervalSec() : req.reportIntervalSec();
        double jitter = req.jitterPct() == null ? 0 : req.jitterPct();
        checkReport(interval, jitter);
        ReportMode mode = parse(req.reportMode(), ReportMode.class, ReportMode.ALWAYS, "reportMode");
        Map<String, Object> effective = PropertyResolver.effective(type.def(), profileOverrides, overrides);
        boolean hasBattery = type.def().metric("battery").isPresent();
        ActuatorState state = type.def().actuator() ? ActuatorState.initial(type.def().capabilities()) : null;
        String name = req.name() == null || req.name().isBlank() ? type.def().name() + "-" + req.deviceId() : req.name();
        String externalId = req.externalId() == null || req.externalId().isBlank() ? defaultExternalId(req.deviceId())
                : req.externalId().toLowerCase();
        String gateway = req.gatewayEui() == null ? SimulationWorld.defaultGateway(organizationId) : req.gatewayEui().toLowerCase();
        return new SimDeviceRepository.DeviceRow(req.deviceId(), organizationId, type.id(), req.profileId(), req.spaceId(), name,
                externalId, sourceId, overrides, mode, sources, interval, jitter, hasBattery ? 100.0 : null,
                hasBattery && effective.get("batteryLifeYears") instanceof Number n && n.doubleValue() <= 0 ? 0.0 : null, format, output,
                0, gateway, req.response(), state, req.seed(), 0);
    }

    /** 가상 기기 기본 외부 ID: {@code 5a1d} + 기기 ID 12자리 16진수(ChirpStack devEui 모양 16자) */
    public static String defaultExternalId(long deviceId) {
        return String.format("5a1d%012x", deviceId);
    }

    private static Map<String, MetricSource> validateSources(DeviceTypeDef type, Map<String, MetricSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Map.of();
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        Map<String, MetricSource> out = new TreeMap<>();
        sources.forEach((metric, src) -> {
            if (type.metric(metric).isEmpty()) {
                errors.add(new FieldErrorDetail("metricSources." + metric, "UNKNOWN_METRIC", "이 유형에 없는 측정 항목입니다"));
            } else if (src == null || (!MetricDef.PHYSICS.equals(src.source()) && !MetricDef.GENERATOR.equals(src.source()))) {
                errors.add(new FieldErrorDetail("metricSources." + metric + ".source", "Pattern", "PHYSICS|GENERATOR"));
            } else if (MetricDef.GENERATOR.equals(src.source())) {
                String problem = Generators.validate(src.generator());
                if (problem != null) {
                    errors.add(new FieldErrorDetail("metricSources." + metric + ".generator." + problem, "Invalid", "생성기 설정 오류"));
                } else {
                    out.put(metric, src);
                }
            } else {
                out.put(metric, src);
            }
        });
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        return out;
    }

    /** PLATFORM_MQTT(SIM-02.07)는 테스트 환경 전용이고 M7 범위라 플랫폼 브로커 출력이 아직 없다. 운영·스테이징은 언제나 거부 */
    private void guardOutput(OutputPath output) {
        if (output == OutputPath.PLATFORM_MQTT) {
            throw new BusinessException(SimErrorCode.SIM_PLATFORM_BROKER_UNAVAILABLE, List.of(new FieldErrorDetail("outputPath",
                    SimErrorCode.SIM_PLATFORM_BROKER_UNAVAILABLE.code(), properties.deployed()
                    ? "운영·스테이징은 내부 직접 주입(INTERNAL)만 씁니다" : "플랫폼 브로커 출력이 설정되지 않았습니다")));
        }
    }

    private static void checkReport(int interval, double jitter) {
        if (interval < 5 || interval > 86_400) {
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE,
                    List.of(new FieldErrorDetail("reportIntervalSec", SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(), "허용 범위: 5~86400")));
        }
        if (jitter < 0 || jitter > 50) {
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE,
                    List.of(new FieldErrorDetail("jitterPct", SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(), "허용 범위: 0~50")));
        }
    }

    /**
     * API-SIM-33 {@code PATCH}: 바꿀 항목만. overrides의 null 값은 그 키를 지워 상위 값으로 되돌린다. 다음 보고부터 반영(SIM-02.03).
     */
    @Transactional
    public Map<String, Object> patch(long organizationId, long deviceId, JsonNode body) {
        SimDeviceRepository.DeviceRow d = devices.findById(organizationId, deviceId)
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        CatalogRepository.TypeRow type = catalog.type(organizationId, d.typeId());
        Long profileId = body.has("profileId") ? (body.get("profileId").isNull() ? null : Long.parseLong(body.get("profileId").asString()))
                : d.profileId();
        if (profileId != null && !profileId.equals(d.profileId())) {
            ProfileRepository.ProfileRow p = profiles.findById(organizationId, profileId)
                    .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
            if (p.typeId() != type.id()) {
                throw invalid("profileId", "TYPE_MISMATCH", "유형이 다른 프로필입니다");
            }
        }
        Map<String, Object> overrides = d.overrides();
        if (body.has("overrides")) {
            Map<String, Object> changes = new LinkedHashMap<>();
            body.get("overrides").properties().forEach(e -> changes.put(e.getKey(), e.getValue().isNull() ? null
                    : net.java21.data2flow.sim.common.Json.MAPPER.convertValue(e.getValue(), Object.class)));
            PropertyValidator.validate(type.def(), changes, "overrides.");
            overrides = PropertyResolver.merge(d.overrides(), changes);
        }
        Map<String, MetricSource> sources = d.metricSources();
        if (body.has("metricSources")) {
            Map<String, MetricSource> merged = new TreeMap<>(d.metricSources());
            body.get("metricSources").properties().forEach(e -> {
                if (e.getValue().isNull()) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), net.java21.data2flow.sim.common.Json.MAPPER.convertValue(e.getValue(), MetricSource.class));
                }
            });
            sources = validateSources(type.def(), merged);
        }
        int interval = body.has("reportIntervalSec") ? body.get("reportIntervalSec").asInt() : d.reportIntervalSec();
        double jitter = body.has("jitterPct") ? body.get("jitterPct").asDouble() : d.jitterPct();
        checkReport(interval, jitter);
        Double drain = body.has("batteryDrainPerReport") ? (body.get("batteryDrainPerReport").isNull() ? null
                : body.get("batteryDrainPerReport").asDouble()) : d.batteryDrainPerReport();
        if (drain != null && (drain < 0 || drain > 100)) {
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE, List.of(new FieldErrorDetail("batteryDrainPerReport",
                    SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(), "허용 범위: 0~100")));
        }
        PayloadFormat format = body.has("payloadFormat") ? parse(body.get("payloadFormat").asString(), PayloadFormat.class,
                d.payloadFormat(), "payloadFormat") : d.payloadFormat();
        OutputPath output = body.has("outputPath") ? parse(body.get("outputPath").asString(), OutputPath.class, d.outputPath(),
                "outputPath") : d.outputPath();
        guardOutput(output);
        ResponseSettings response = body.has("response") ? net.java21.data2flow.sim.common.Json.MAPPER.convertValue(body.get("response"),
                ResponseSettings.class) : d.response();
        if (response != null && response.failurePct() != null && (response.failurePct() < 0 || response.failurePct() > 100)) {
            throw new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE, List.of(new FieldErrorDetail("response.failurePct",
                    SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE.code(), "허용 범위: 0~100")));
        }
        ReportMode mode = body.has("reportMode") ? parse(body.get("reportMode").asString(), ReportMode.class, d.reportMode(), "reportMode")
                : d.reportMode();
        Long seed = body.has("seed") ? (body.get("seed").isNull() ? null : body.get("seed").asLong()) : d.seed();
        String gateway = body.has("gatewayEui") ? body.get("gatewayEui").asString() : d.gatewayEui();
        SimDeviceRepository.DeviceRow next = new SimDeviceRepository.DeviceRow(d.deviceId(), organizationId, d.typeId(), profileId,
                d.spaceId(), d.name(), d.externalId(), d.sourceId(), overrides, mode, sources, interval, jitter, d.batteryPct(), drain,
                format, output, d.frameCounter(), gateway, response, d.actuatorState(), seed, d.version());
        devices.update(next);
        executor.devicesChanged(organizationId, List.of(deviceId));
        return view(devices.findById(organizationId, deviceId).orElseThrow());
    }

    /** API-SIM-07(simulator 쪽): 실행 중 시나리오가 쓰는 공간의 기기는 지울 수 없다 */
    @Transactional
    public void delete(long organizationId, long deviceId) {
        SimDeviceRepository.DeviceRow d = devices.findById(organizationId, deviceId)
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if (runs.findBusySpaces(organizationId).contains(d.spaceId())) {
            throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
        }
        devices.delete(organizationId, deviceId);
        executor.devicesChanged(organizationId, List.of(deviceId));
    }

    /** API-SIM-09 조회: 특성과 출처(CATALOG·PROFILE·DEVICE) */
    public Map<String, Object> get(long organizationId, long deviceId) {
        return view(devices.findById(organizationId, deviceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND)));
    }

    Map<String, Object> view(SimDeviceRepository.DeviceRow d) {
        CatalogRepository.TypeRow type = catalog.type(d.organizationId(), d.typeId());
        Map<String, Object> profile = d.profileId() == null ? Map.of()
                : profiles.findById(d.organizationId(), d.profileId()).map(ProfileRepository.ProfileRow::overrides).orElse(Map.of());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", Long.toString(d.deviceId()));
        m.put("name", d.name());
        m.put("typeId", Long.toString(d.typeId()));
        m.put("typeKey", type.def().key());
        m.put("category", type.def().category());
        m.put("profileId", d.profileId() == null ? null : Long.toString(d.profileId()));
        m.put("spaceId", Long.toString(d.spaceId()));
        m.put("externalId", d.externalId());
        m.put("sourceId", Long.toString(d.sourceId()));
        m.put("properties", PropertyResolver.resolve(type.def(), profile, d.overrides()));
        m.put("metricSources", d.metricSources());
        m.put("reportIntervalSec", d.reportIntervalSec());
        m.put("jitterPct", d.jitterPct());
        m.put("battery", d.batteryPct());
        m.put("batteryDrainPerReport", d.batteryDrainPerReport());
        m.put("payloadFormat", d.payloadFormat());
        m.put("outputPath", d.outputPath());
        m.put("response", d.response());
        m.put("actuatorState", executor.actuatorState(d.deviceId()).orElse(d.actuatorState()));
        m.put("seed", d.seed());
        m.put("reportMode", d.reportMode());
        m.put("gatewayEui", d.gatewayEui());
        m.put("version", d.version());
        return m;
    }

    // ───────────── 키트(API-SIM-06) ─────────────

    /** core가 만든 기기 ID 묶음 */
    public record PlacedDevice(Long deviceId, String typeKey, String name, String externalId, Long sourceId) {
    }

    /** @param profileOverrides 유형 키 → 프로필 ID */
    public record KitRequest(Long spaceId, List<PlacedDevice> devices, Map<String, Long> profileOverrides) {
    }

    /** 키트 배치: 원자적(BR-SIM-19), 키트 구성과 기기 수가 맞아야 한다. 측정·제어 관계와 제안 플로우를 돌려준다 */
    @Transactional
    public Map<String, Object> placeKit(long organizationId, String kitKey, KitRequest req) {
        KitDef kit = BuiltinCatalog.kit(kitKey).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        List<PlacedDevice> placed = req.devices() == null ? List.of() : req.devices();
        Map<String, Integer> expected = new TreeMap<>();
        kit.items().forEach(i -> expected.merge(i.typeKey(), i.count(), Integer::sum));
        Map<String, Integer> actual = new TreeMap<>();
        placed.forEach(p -> actual.merge(p.typeKey(), 1, Integer::sum));
        if (!expected.equals(actual)) {
            throw invalid("devices", "KIT_MISMATCH", "키트 구성과 다릅니다: " + expected);
        }
        List<CreateRequest> requests = new ArrayList<>();
        Map<String, Integer> ordinal = new TreeMap<>();
        Map<String, List<Long>> byType = new TreeMap<>();
        for (PlacedDevice p : placed) {
            KitDef.Item item = kit.items().stream().filter(i -> i.typeKey().equals(p.typeKey())).findFirst().orElseThrow();
            int n = ordinal.merge(p.typeKey(), 1, Integer::sum);
            String name = p.name() != null ? p.name() : item.namePrefix() + "-" + n;
            Long profile = req.profileOverrides() == null ? null : req.profileOverrides().get(p.typeKey());
            requests.add(new CreateRequest(p.deviceId(), null, p.typeKey(), profile, req.spaceId(), null, null, name, p.externalId(),
                    p.sourceId(), null, null, null, null, null, null, null, null));
            byType.computeIfAbsent(p.typeKey(), k -> new ArrayList<>()).add(p.deviceId());
        }
        List<Map<String, Object>> created = createAll(organizationId, requests);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < created.size(); i++) {
            PlacedDevice p = placed.get(i);
            KitDef.Item item = kit.items().stream().filter(x -> x.typeKey().equals(p.typeKey())).findFirst().orElseThrow();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", String.valueOf(created.get(i).get("deviceId")));   // api-rules: ID는 문자열
            m.put("name", created.get(i).get("name"));
            m.put("typeKey", p.typeKey());
            m.put("relation", item.relation());
            out.add(m);
        }
        List<Map<String, Object>> flows = new ArrayList<>();
        for (String template : kit.suggestedFlowTemplates()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("templateKey", template);
            f.put("name", BuiltinCatalog.FLOW_HOT_THEN_COOL.equals(template) ? "고온이면 냉방" : "CO2 높으면 환기");
            f.put("bindings", flowBindings(template, req.spaceId(), byType));
            flows.add(f);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("spaceId", Long.toString(req.spaceId()));
        r.put("devices", out);
        r.put("suggestedFlows", flows);
        return r;
    }

    static Map<String, Object> flowBindings(String template, Long spaceId, Map<String, List<Long>> byType) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("spaceId", Long.toString(spaceId));
        if (BuiltinCatalog.FLOW_HOT_THEN_COOL.equals(template)) {
            b.put("temperatureSensorIds", ids(byType.get(BuiltinCatalog.TH_SENSOR)));
            b.put("airconId", first(byType.get(BuiltinCatalog.AIRCON)));
            b.put("thresholdC", 27);
            b.put("durationMin", 5);
            b.put("setpointC", 24);
        } else {
            List<Long> co2 = byType.getOrDefault(BuiltinCatalog.CO2_SENSOR, byType.get(BuiltinCatalog.AQ_SENSOR));
            b.put("co2SensorIds", ids(co2));
            b.put("ventilatorId", first(byType.get(BuiltinCatalog.VENTILATOR)));
            b.put("thresholdPpm", 1000);
            b.put("durationMin", 5);
            b.put("level", 3);
        }
        return b;
    }

    private static List<String> ids(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().map(String::valueOf).toList();
    }

    private static String first(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null : Long.toString(ids.get(0));
    }

    private static <E extends Enum<E>> E parse(String raw, Class<E> type, E fallback, String field) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "Pattern", String.join("|", java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        }
    }

    static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
