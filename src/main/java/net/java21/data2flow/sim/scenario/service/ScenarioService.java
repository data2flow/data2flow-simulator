package net.java21.data2flow.sim.scenario.service;

import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.sim.catalog.domain.KitDef;
import net.java21.data2flow.sim.catalog.repository.CatalogRepository;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.device.domain.ResponseSettings;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.device.service.DeviceService;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.scenario.domain.DemoPresets;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.Scenario;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import net.java21.data2flow.sim.scenario.domain.ScenarioValidator;
import net.java21.data2flow.sim.scenario.repository.ScenarioRepository;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import net.java21.data2flow.sim.space.service.SpaceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 시나리오(SIM-04.01·04.05, SIM-08.02, API-SIM-12·13·18·26·27의 simulator 쪽) */
@Service
public class ScenarioService {

    public static final int SCHEMA_VERSION = 1;
    private static final YAMLMapper YAML = YAMLMapper.builder().findAndAddModules().build();

    private final ScenarioRepository scenarios;
    private final SpacePhysicsRepository spaces;
    private final SimDeviceRepository devices;
    private final CatalogRepository catalog;
    private final RunRepository runs;
    private final DeviceService deviceService;
    private final SpaceService spaceService;

    public ScenarioService(ScenarioRepository scenarios, SpacePhysicsRepository spaces, SimDeviceRepository devices,
                           CatalogRepository catalog, RunRepository runs, DeviceService deviceService, SpaceService spaceService) {
        this.scenarios = scenarios;
        this.spaces = spaces;
        this.devices = devices;
        this.catalog = catalog;
        this.runs = runs;
        this.deviceService = deviceService;
        this.spaceService = spaceService;
    }

    /** API-SIM-12 본문: Scenario + baseVersion(수정) */
    public record ScenarioRequest(Scenario scenario, Integer baseVersion) {
    }

    @Transactional
    public Map<String, Object> create(long organizationId, Long userId, Scenario s) {
        validate(organizationId, s);
        try {
            long id = scenarios.insert(organizationId, s, userId);
            return view(scenarios.findById(organizationId, id).orElseThrow());
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
    }

    @Transactional
    public Map<String, Object> update(long organizationId, long id, Long userId, Scenario s, Integer baseVersion) {
        ScenarioRepository.ScenarioRow row = find(organizationId, id);
        if (baseVersion == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail(VersionCheck.BASE_VERSION, "NotNull", "baseVersion이 필요합니다")));
        }
        VersionCheck.require(baseVersion, row.version());
        validate(organizationId, s);
        try {
            VersionCheck.requireUpdated(scenarios.update(organizationId, id, s, row.version(), userId));
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        return view(find(organizationId, id));
    }

    /** 삭제: 실행 중이면 409 */
    @Transactional
    public void delete(long organizationId, long id) {
        ScenarioRepository.ScenarioRow row = find(organizationId, id);
        if (!runs.findActiveUsingSpaces(organizationId, row.scenario().spaceIds(), -1).isEmpty()
                && runs.listRecent(organizationId, 50).stream().anyMatch(r -> r.scenarioId() != null && r.scenarioId() == id
                && r.status().active())) {
            throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
        }
        scenarios.delete(organizationId, id);
    }

    /** API-SIM-13 복제: 새 ID, 이름 기본 "{원래} (복사본)" */
    @Transactional
    public Map<String, Object> cloneScenario(long organizationId, long id, Long userId, String name) {
        ScenarioRepository.ScenarioRow row = find(organizationId, id);
        String newName = name == null || name.isBlank() ? uniqueName(organizationId, row.scenario().name() + " (복사본)") : name;
        Scenario copy = new Scenario(newName, row.scenario().spaceIds(), row.scenario().simStartAt(), row.scenario().durationSec(),
                row.scenario().seed(), row.scenario().useCalendar(), row.scenario().outdoor(), row.scenario().events(),
                row.scenario().expectations(), null);
        try {
            return Map.of("id", Long.toString(scenarios.insert(organizationId, copy, userId)));
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
    }

    public Map<String, Object> get(long organizationId, long id) {
        return view(find(organizationId, id));
    }

    public ListApiResponse<Map<String, Object>> list(long organizationId, Integer page, Integer size, String keyword) {
        PageParams p = PageParams.of(page, size);
        String kw = PageParams.keyword(keyword);
        List<Map<String, Object>> rows = scenarios.page(organizationId, kw, p.size(), p.offset()).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenarioId", Long.toString(r.id()));
            m.put("name", r.scenario().name());
            m.put("spaceIds", r.scenario().spaceIds().stream().map(String::valueOf).toList());
            m.put("durationSec", r.scenario().durationSec());
            m.put("presetKey", r.scenario().presetKey());
            m.put("expectationCount", r.scenario().expectations().size());
            m.put("updatedAt", r.updatedAt());
            return m;
        }).toList();
        return ListApiResponse.of(p, rows, scenarios.count(organizationId, kw));
    }

    ScenarioRepository.ScenarioRow find(long organizationId, long id) {
        return scenarios.findById(organizationId, id).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
    }

    void validate(long organizationId, Scenario s) {
        if (s == null) {
            throw new BusinessException(SimErrorCode.SIM_SCENARIO_INVALID, List.of(new FieldErrorDetail("scenario",
                    SimErrorCode.SIM_SCENARIO_INVALID.code(), "필수")));
        }
        Set<Long> knownSpaces = new HashSet<>(spaces.findAll(organizationId).stream().map(SpacePhysicsRepository.SpaceRow::spaceId).toList());
        Set<Long> knownDevices = new HashSet<>(devices.findAll(organizationId).stream().map(SimDeviceRepository.DeviceRow::deviceId).toList());
        ScenarioValidator.validate(s, knownSpaces, knownDevices);
    }

    static Map<String, Object> view(ScenarioRepository.ScenarioRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        Scenario s = r.scenario();
        m.put("scenarioId", Long.toString(r.id()));
        m.put("name", s.name());
        m.put("spaceIds", s.spaceIds().stream().map(String::valueOf).toList());
        m.put("simStartAt", s.simStartAt());
        m.put("durationSec", s.durationSec());
        m.put("seed", s.seed());
        m.put("useCalendar", s.useCalendar());
        m.put("outdoor", s.outdoor());
        m.put("events", s.events());
        m.put("expectations", s.expectations());
        m.put("presetKey", s.presetKey());
        m.put("schemaVersion", r.schemaVersion());
        m.put("version", r.version());
        m.put("updatedAt", r.updatedAt());
        return m;
    }

    private String uniqueName(long organizationId, String base) {
        String name = base.length() > 80 ? base.substring(0, 80) : base;
        int n = 2;
        while (scenarios.findByName(organizationId, name).isPresent()) {
            String suffix = " " + n++;
            name = (base.length() + suffix.length() > 80 ? base.substring(0, 80 - suffix.length()) : base) + suffix;
        }
        return name;
    }

    private static BusinessException duplicateName() {
        return new BusinessException(CommonErrorCode.VERSION_CONFLICT,
                List.of(new FieldErrorDetail("name", "DUPLICATE", "같은 이름의 시나리오가 있습니다")));
    }

    // ───────────── 데모 프리셋(API-SIM-18) ─────────────

    public List<Map<String, Object>> presets(long organizationId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DemoPresets.PresetDef p : DemoPresets.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("name", p.name());
            m.put("description", p.description());
            m.put("estimatedMinutes", p.estimatedMinutes());
            m.put("spaceName", p.spaceName());
            m.put("spacePreset", p.spacePreset());
            m.put("devices", p.devices());
            m.put("flowTemplates", p.flowTemplates().keySet());
            var existing = scenarios.findByPreset(organizationId, p.key());
            m.put("state", existing.isEmpty() ? "NOT_PREPARED"
                    : runs.listRecent(organizationId, 50).stream().anyMatch(r -> r.scenarioId() != null
                    && r.scenarioId() == existing.get().id() && r.status().active()) ? "RUNNING" : "PREPARED");
            m.put("scenarioId", existing.map(r -> Long.toString(r.id())).orElse(null));
            list.add(m);
        }
        return list;
    }

    /** core가 프리셋 공간·기기를 만든 뒤 넘기는 묶음 */
    public record PrepareRequest(Long spaceId, List<DeviceService.PlacedDevice> devices) {
    }

    /** 프리셋 준비(BR-SIM-18): 원자적, 이미 준비됐으면 그대로(reused) */
    @Transactional
    public Map<String, Object> preparePreset(long organizationId, Long userId, String presetKey, PrepareRequest req) {
        DemoPresets.PresetDef p = DemoPresets.find(presetKey).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        var existing = scenarios.findByPreset(organizationId, presetKey);
        if (existing.isPresent()) {
            Scenario s = existing.get().scenario();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scenarioId", Long.toString(existing.get().id()));
            r.put("spaceIds", s.spaceIds().stream().map(String::valueOf).toList());
            r.put("deviceIds", devices.findBySpaces(organizationId, s.spaceIds()).stream().map(d -> Long.toString(d.deviceId())).toList());
            r.put("flowTemplates", List.of());
            r.put("reused", true);
            return r;
        }
        if (req.spaceId() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("spaceId", "NotNull", "공간이 필요합니다")));
        }
        spaceService.upsert(organizationId, req.spaceId(), new SpaceService.SpaceRequest(p.spaceName(), p.spacePreset().name(), null));
        List<DeviceService.PlacedDevice> placed = req.devices() == null ? List.of() : req.devices();
        Map<String, Integer> expected = new TreeMap<>();
        p.devices().forEach(i -> expected.merge(i.typeKey(), i.count(), Integer::sum));
        Map<String, Integer> actual = new TreeMap<>();
        placed.forEach(d -> actual.merge(d.typeKey(), 1, Integer::sum));
        if (!expected.equals(actual)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("devices", "PRESET_MISMATCH", "프리셋 구성과 다릅니다: " + expected)));
        }
        List<DeviceService.CreateRequest> creates = new ArrayList<>();
        Map<String, List<Long>> byType = new TreeMap<>();
        Map<String, Integer> ordinal = new TreeMap<>();
        for (DeviceService.PlacedDevice d : placed) {
            KitDef.Item item = p.devices().stream().filter(i -> i.typeKey().equals(d.typeKey())).findFirst().orElseThrow();
            int n = ordinal.merge(d.typeKey(), 1, Integer::sum);
            creates.add(new DeviceService.CreateRequest(d.deviceId(), null, d.typeKey(), null, req.spaceId(), "ALWAYS", null,
                    d.name() == null ? item.namePrefix() + "-" + n : d.name(), d.externalId(), d.sourceId(), null, null, null, null,
                    null, null, null, null));
            byType.computeIfAbsent(d.typeKey(), k -> new ArrayList<>()).add(d.deviceId());
        }
        deviceService.createAll(organizationId, creates);
        Scenario scenario = DemoPresets.materialize(p, uniqueName(organizationId, p.name()), req.spaceId(), byType,
                SimulationWorld.defaultGateway(organizationId));
        long scenarioId = scenarios.insert(organizationId, scenario, userId);
        List<Map<String, Object>> flows = new ArrayList<>();
        DemoPresets.flowBindings(p, req.spaceId(), byType, SimulationWorld.defaultGateway(organizationId)).forEach((k, v) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("templateKey", k);
            f.put("bindings", v);
            flows.add(f);
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenarioId", Long.toString(scenarioId));
        r.put("spaceIds", List.of(Long.toString(req.spaceId())));
        r.put("deviceIds", placed.stream().map(d -> Long.toString(d.deviceId())).toList());
        r.put("flowTemplates", flows);
        r.put("reused", false);
        return r;
    }

    // ───────────── 내보내기·가져오기(SIM-08.02, API-SIM-26·27) ─────────────

    /** 내보내기 파일 모양(스키마 버전 1) */
    public record ExportFile(int schemaVersion, Scenario scenario, List<ExportSpace> spaces, List<ExportDevice> devices, Long seed) {
    }

    public record ExportSpace(long spaceId, SpacePhysics physics, boolean sandbox) {
    }

    public record ExportDevice(long deviceId, String name, String typeKey, long spaceId, Map<String, Object> overrides, String reportMode,
                               Map<String, MetricSource> metricSources, int reportIntervalSec, double jitterPct, String payloadFormat,
                               ResponseSettings response, Long seed, String gatewayEui) {
    }

    public byte[] export(long organizationId, long id, String format) {
        Scenario s = find(organizationId, id).scenario();
        List<ExportSpace> sp = spaces.findByIds(organizationId, s.spaceIds()).stream()
                .map(x -> new ExportSpace(x.spaceId(), x.physics(), x.sandbox())).toList();
        List<ExportDevice> dv = new ArrayList<>();
        for (SimDeviceRepository.DeviceRow d : devices.findBySpaces(organizationId, s.spaceIds())) {
            String typeKey = catalog.loadType(d.typeId()).map(t -> t.def().key()).orElse("unknown");
            dv.add(new ExportDevice(d.deviceId(), d.name(), typeKey, d.spaceId(), d.overrides(), d.reportMode().name(), d.metricSources(),
                    d.reportIntervalSec(), d.jitterPct(), d.payloadFormat().name(), d.response(), d.seed(), d.gatewayEui()));
        }
        ExportFile file = new ExportFile(SCHEMA_VERSION, s, sp, dv, s.seed());
        if ("yaml".equalsIgnoreCase(format)) {
            return YAML.writeValueAsBytes(Json.MAPPER.valueToTree(file));
        }
        return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(file);
    }

    /** 파일 읽기(JSON 또는 YAML). 형식·스키마 버전·필수 항목 오류는 SIM_IMPORT_INVALID(위치 포함) */
    public static ExportFile parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw importInvalid("$", "빈 파일");
        }
        JsonNode tree;
        try {
            String text = new String(content, StandardCharsets.UTF_8).stripLeading();
            tree = text.startsWith("{") ? Json.MAPPER.readTree(content) : YAML.readTree(content);
        } catch (RuntimeException e) {
            throw importInvalid("$", "JSON·YAML로 읽을 수 없습니다");
        }
        if (tree == null || !tree.isObject()) {
            throw importInvalid("$", "객체가 아닙니다");
        }
        if (tree.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
            throw importInvalid("schemaVersion", "지원하는 버전은 " + SCHEMA_VERSION + "입니다");
        }
        if (!tree.path("scenario").isObject()) {
            throw importInvalid("scenario", "필수");
        }
        try {
            ExportFile f = Json.MAPPER.treeToValue(tree, ExportFile.class);
            if (f.scenario().simStartAt() == null) {
                throw importInvalid("scenario.simStartAt", "필수");
            }
            if (f.scenario().name() == null) {
                throw importInvalid("scenario.name", "필수");
            }
            return f;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw importInvalid("$", "형식 오류: " + firstLine(e.getMessage()));
        }
    }

    /**
     * 가져오기. dryRun이면 만들 것과 충돌만 알려 준다(core가 이 목록으로 공간·기기 기준 정보를 만든다). 확정이면 core가 만든 ID 대응표
     * (idMap {spaces:{old:new}, devices:{old:new}})로 물리·기기 설정·시나리오를 만든다.
     */
    @Transactional
    public Map<String, Object> importFile(long organizationId, Long userId, byte[] content, boolean dryRun, Map<String, Map<String, Long>> idMap) {
        ExportFile f = parse(content);
        if (dryRun) {
            Map<String, Object> will = new LinkedHashMap<>();
            will.put("spaces", f.spaces().size());
            will.put("devices", f.devices().size());
            will.put("scenarios", 1);
            List<Map<String, Object>> conflicts = new ArrayList<>();
            if (scenarios.findByName(organizationId, f.scenario().name()).isPresent()) {
                conflicts.add(Map.of("kind", "SCENARIO", "name", f.scenario().name(), "resolution", "RENAME"));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("willCreate", will);
            r.put("conflicts", conflicts);
            r.put("spaces", f.spaces().stream().map(s -> Map.of("oldId", Long.toString(s.spaceId()), "preset", s.physics().preset())).toList());
            r.put("devices", f.devices().stream().map(d -> Map.of("oldId", Long.toString(d.deviceId()), "name", d.name(), "typeKey",
                    d.typeKey(), "spaceOldId", Long.toString(d.spaceId()))).toList());
            return r;
        }
        Map<String, Long> spaceMap = idMap == null ? Map.of() : idMap.getOrDefault("spaces", Map.of());
        Map<String, Long> deviceMap = idMap == null ? Map.of() : idMap.getOrDefault("devices", Map.of());
        for (ExportSpace s : f.spaces()) {
            Long target = spaceMap.get(Long.toString(s.spaceId()));
            if (target == null) {
                throw importInvalid("idMap.spaces." + s.spaceId(), "새 공간 ID가 필요합니다");
            }
            spaceService.upsert(organizationId, target, new SpaceService.SpaceRequest(null, s.physics().preset().name(), s.physics()));
        }
        List<DeviceService.CreateRequest> creates = new ArrayList<>();
        for (ExportDevice d : f.devices()) {
            Long target = deviceMap.get(Long.toString(d.deviceId()));
            Long space = spaceMap.get(Long.toString(d.spaceId()));
            if (target == null || space == null) {
                throw importInvalid("idMap.devices." + d.deviceId(), "새 기기 ID가 필요합니다");
            }
            creates.add(new DeviceService.CreateRequest(target, null, d.typeKey(), null, space, d.reportMode(), d.overrides(), d.name(),
                    null, null, d.metricSources(), d.reportIntervalSec(), d.jitterPct(), d.payloadFormat(), null, d.response(), d.seed(),
                    null));
        }
        if (!creates.isEmpty()) {
            deviceService.createAll(organizationId, creates);
        }
        Scenario remapped = remap(f.scenario(), spaceMap, deviceMap, uniqueName(organizationId, f.scenario().name()));
        validate(organizationId, remapped);
        long scenarioId = scenarios.insert(organizationId, remapped, userId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenarioId", Long.toString(scenarioId));
        r.put("spaceIds", remapped.spaceIds().stream().map(String::valueOf).toList());
        r.put("deviceIds", creates.stream().map(c -> Long.toString(c.deviceId())).toList());
        return r;
    }

    static Scenario remap(Scenario s, Map<String, Long> spaces, Map<String, Long> devices, String name) {
        List<Long> spaceIds = s.spaceIds().stream().map(id -> spaces.getOrDefault(Long.toString(id), id)).toList();
        List<ScenarioEvent> events = s.events().stream().map(e -> new ScenarioEvent(e.id(), e.track(), e.at(), e.until(),
                remapTarget(e.target(), spaces, devices), e.params())).toList();
        List<Expectation> expectations = s.expectations().stream().map(x -> new Expectation(x.id(), x.kind(),
                remapTarget(x.target(), spaces, devices), x.condition(), x.deadline())).toList();
        return new Scenario(name, spaceIds, s.simStartAt(), s.durationSec(), s.seed(), s.useCalendar(), s.outdoor(), events, expectations,
                s.presetKey());
    }

    static Map<String, Object> remapTarget(Map<String, Object> target, Map<String, Long> spaces, Map<String, Long> devices) {
        Map<String, Object> out = new TreeMap<>(target);
        if (target.get("spaceId") != null) {
            out.put("spaceId", spaces.getOrDefault(String.valueOf(target.get("spaceId")), toLong(target.get("spaceId"))));
        }
        if (target.get("deviceId") != null) {
            out.put("deviceId", devices.getOrDefault(String.valueOf(target.get("deviceId")), toLong(target.get("deviceId"))));
        }
        if (target.get("targetIds") instanceof List<?> ids) {
            out.put("targetIds", ids.stream().map(i -> devices.containsKey(String.valueOf(i))
                    ? Long.toString(devices.get(String.valueOf(i))) : String.valueOf(i)).toList());
        }
        return out;
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    static BusinessException importInvalid(String path, String reason) {
        return new BusinessException(SimErrorCode.SIM_IMPORT_INVALID,
                List.of(new FieldErrorDetail(path, SimErrorCode.SIM_IMPORT_INVALID.code(), reason)));
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
