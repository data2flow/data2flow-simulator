package net.java21.data2flow.sim.space.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.sim.common.OrgId;
import net.java21.data2flow.sim.space.service.SpaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 가상 공간 물리 설정 내부 API(API-SIM-10·11·24의 simulator 쪽) */
@RestController
public class InternalSpaceController {

    private final SpaceService spaces;

    public InternalSpaceController(SpaceService spaces) {
        this.spaces = spaces;
    }

    @GetMapping("/internal/sim/spaces")
    public ApiResponse<List<Map<String, Object>>> list(@OrgId long organizationId) {
        return ApiResponse.success(spaces.list(organizationId));
    }

    /** core가 DEV 공간(virtual)을 만든 뒤 같은 ID로 물리 설정을 둔다(처음이면 만들고 있으면 바꿈) */
    @PutMapping("/internal/sim/spaces/{space-id}")
    public ApiResponse<Map<String, Object>> upsert(@OrgId long organizationId, @PathVariable("space-id") long spaceId,
                                                   @RequestBody SpaceService.SpaceRequest body) {
        return ApiResponse.success(spaces.upsert(organizationId, spaceId, body));
    }

    @GetMapping("/internal/sim/spaces/{space-id}")
    public ApiResponse<Map<String, Object>> get(@OrgId long organizationId, @PathVariable("space-id") long spaceId) {
        return ApiResponse.success(spaces.get(organizationId, spaceId));
    }

    @DeleteMapping("/internal/sim/spaces/{space-id}")
    public ResponseEntity<Void> delete(@OrgId long organizationId, @PathVariable("space-id") long spaceId) {
        spaces.delete(organizationId, spaceId);
        return ResponseEntity.noContent().build();
    }

    public record SandboxRequest(boolean sandbox) {
    }

    @PutMapping("/internal/sim/spaces/{space-id}/sandbox")
    public ApiResponse<Map<String, Object>> sandbox(@OrgId long organizationId, @PathVariable("space-id") long spaceId,
                                                    @RequestBody SandboxRequest body) {
        return ApiResponse.success(spaces.sandbox(organizationId, spaceId, body.sandbox()));
    }

    @PostMapping("/internal/sim/preview")
    public ApiResponse<Map<String, Object>> preview(@OrgId long organizationId, @RequestBody SpaceService.PreviewRequest body) {
        return ApiResponse.success(spaces.preview(organizationId, body));
    }
}
