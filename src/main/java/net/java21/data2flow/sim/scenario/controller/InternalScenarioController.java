package net.java21.data2flow.sim.scenario.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.common.OrgId;
import net.java21.data2flow.sim.common.Users;
import net.java21.data2flow.sim.scenario.service.ScenarioService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** 시나리오·프리셋·가져오기/내보내기 내부 API(API-SIM-12·13·18·26·27의 simulator 쪽) */
@RestController
public class InternalScenarioController {

    private final ScenarioService scenarios;

    public InternalScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping("/internal/sim/scenarios")
    public ListApiResponse<Map<String, Object>> list(@OrgId long organizationId, @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String keyword) {
        return scenarios.list(organizationId, page, size, keyword);
    }

    @PostMapping("/internal/sim/scenarios")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@OrgId long organizationId,
                                                                   @RequestBody net.java21.data2flow.sim.scenario.domain.Scenario body) {
        Map<String, Object> s = scenarios.create(organizationId, Users.current(), body);
        return ResponseEntity.created(URI.create("/internal/sim/scenarios/" + s.get("scenarioId"))).body(ApiResponse.success(s));
    }

    @GetMapping("/internal/sim/scenarios/{scenario-id}")
    public ApiResponse<Map<String, Object>> get(@OrgId long organizationId, @PathVariable("scenario-id") long id) {
        return ApiResponse.success(scenarios.get(organizationId, id));
    }

    /** 본문: Scenario 필드 + baseVersion */
    @PutMapping("/internal/sim/scenarios/{scenario-id}")
    public ApiResponse<Map<String, Object>> update(@OrgId long organizationId, @PathVariable("scenario-id") long id,
                                                   @RequestBody tools.jackson.databind.JsonNode body) {
        Integer base = body.has("baseVersion") && !body.get("baseVersion").isNull() ? body.get("baseVersion").asInt() : null;
        var scenario = Json.MAPPER.treeToValue(body, net.java21.data2flow.sim.scenario.domain.Scenario.class);
        return ApiResponse.success(scenarios.update(organizationId, id, Users.current(), scenario, base));
    }

    @DeleteMapping("/internal/sim/scenarios/{scenario-id}")
    public ResponseEntity<Void> delete(@OrgId long organizationId, @PathVariable("scenario-id") long id) {
        scenarios.delete(organizationId, id);
        return ResponseEntity.noContent().build();
    }

    public record CloneRequest(String name) {
    }

    @PostMapping("/internal/sim/scenarios/{scenario-id}/clone")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cloneScenario(@OrgId long organizationId, @PathVariable("scenario-id") long id,
                                                                          @RequestBody(required = false) CloneRequest body) {
        Map<String, Object> r = scenarios.cloneScenario(organizationId, id, Users.current(), body == null ? null : body.name());
        return ResponseEntity.created(URI.create("/internal/sim/scenarios/" + r.get("id"))).body(ApiResponse.success(r));
    }

    /** API-SIM-26: 파일(공통 응답 봉투 없음) */
    @GetMapping("/internal/sim/scenarios/{scenario-id}/export")
    public ResponseEntity<byte[]> export(@OrgId long organizationId, @PathVariable("scenario-id") long id,
                                         @RequestParam(defaultValue = "json") String format) {
        boolean yaml = "yaml".equalsIgnoreCase(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"scenario-" + id + (yaml ? ".yaml" : ".json") + "\"")
                .contentType(yaml ? MediaType.parseMediaType("application/yaml") : MediaType.APPLICATION_JSON)
                .body(scenarios.export(organizationId, id, format));
    }

    /** API-SIM-27: multipart(file, idMap) — dryRun이면 만들 것과 충돌만 */
    @PostMapping(value = "/internal/sim/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> importFile(@OrgId long organizationId,
                                                                       @RequestPart("file") MultipartFile file,
                                                                       @RequestPart(value = "idMap", required = false) String idMap,
                                                                       @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        Map<String, Map<String, Long>> map = idMap == null ? null : Json.read(idMap, new TypeReference<Map<String, Map<String, Long>>>() {
        });
        Map<String, Object> r = scenarios.importFile(organizationId, Users.current(), file.getBytes(), dryRun, map);
        return dryRun ? ResponseEntity.ok(ApiResponse.success(r)) : ResponseEntity.status(201).body(ApiResponse.success(r));
    }

    @GetMapping("/internal/sim/presets")
    public ApiResponse<List<Map<String, Object>>> presets(@OrgId long organizationId) {
        return ApiResponse.success(scenarios.presets(organizationId));
    }

    @PostMapping("/internal/sim/presets/{preset-key}/prepare")
    public ApiResponse<Map<String, Object>> prepare(@OrgId long organizationId, @PathVariable("preset-key") String key,
                                                    @RequestBody ScenarioService.PrepareRequest body) {
        return ApiResponse.success(scenarios.preparePreset(organizationId, Users.current(), key, body));
    }
}
