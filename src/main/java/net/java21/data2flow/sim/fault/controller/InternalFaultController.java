package net.java21.data2flow.sim.fault.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.sim.common.OrgId;
import net.java21.data2flow.sim.fault.service.FaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 장애 주입 내부 API(API-SIM-20·21의 simulator 쪽) */
@RestController
public class InternalFaultController {

    private final FaultService faults;

    public InternalFaultController(FaultService faults) {
        this.faults = faults;
    }

    @PostMapping("/internal/sim/faults")
    public ResponseEntity<ApiResponse<Map<String, Object>>> inject(@OrgId long organizationId, @RequestBody FaultService.InjectRequest body) {
        return ResponseEntity.status(201).body(ApiResponse.success(faults.inject(organizationId, body)));
    }

    @GetMapping("/internal/sim/faults")
    public ListApiResponse<Map<String, Object>> list(@OrgId long organizationId, @RequestParam(required = false) Long runId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return faults.list(organizationId, runId, status, page, size);
    }

    @PostMapping("/internal/sim/faults/{fault-id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@OrgId long organizationId, @PathVariable("fault-id") long faultId) {
        return ApiResponse.success(faults.cancel(organizationId, faultId));
    }
}
