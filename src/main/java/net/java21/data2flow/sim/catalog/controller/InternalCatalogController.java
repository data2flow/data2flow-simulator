package net.java21.data2flow.sim.catalog.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.sim.catalog.service.CatalogService;
import net.java21.data2flow.sim.common.OrgId;
import net.java21.data2flow.sim.common.Users;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** 카탈로그·프로필 내부 API(API-SIM-02·08의 simulator 쪽). 외부 경로는 core-api {@code /api/v1/core/sim/**} */
@RestController
public class InternalCatalogController {

    private final CatalogService catalog;

    public InternalCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/internal/sim/catalog")
    public ApiResponse<Map<String, Object>> catalog(@OrgId long organizationId, @RequestParam(required = false) String category) {
        return ApiResponse.success(catalog.catalog(organizationId, category));
    }

    @GetMapping("/internal/sim/profiles")
    public ApiResponse<List<Map<String, Object>>> profiles(@OrgId long organizationId) {
        return ApiResponse.success(catalog.profiles(organizationId));
    }

    @PostMapping("/internal/sim/profiles")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@OrgId long organizationId,
                                                                   @RequestBody CatalogService.ProfileRequest body) {
        Map<String, Object> p = catalog.createProfile(organizationId, Users.current(), body);
        return ResponseEntity.created(URI.create("/internal/sim/profiles/" + p.get("id"))).body(ApiResponse.success(p));
    }

    @GetMapping("/internal/sim/profiles/{profile-id}")
    public ApiResponse<Map<String, Object>> get(@OrgId long organizationId, @PathVariable("profile-id") long id) {
        return ApiResponse.success(catalog.profile(organizationId, id));
    }

    @PutMapping("/internal/sim/profiles/{profile-id}")
    public ApiResponse<Map<String, Object>> update(@OrgId long organizationId, @PathVariable("profile-id") long id,
                                                   @RequestBody CatalogService.ProfileRequest body) {
        return ApiResponse.success(catalog.updateProfile(organizationId, id, Users.current(), body));
    }

    @DeleteMapping("/internal/sim/profiles/{profile-id}")
    public ResponseEntity<Void> delete(@OrgId long organizationId, @PathVariable("profile-id") long id) {
        catalog.deleteProfile(organizationId, id);
        return ResponseEntity.noContent().build();
    }
}
