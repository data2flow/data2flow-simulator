package net.java21.data2flow.sim.run.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.sim.common.OrgId;
import net.java21.data2flow.sim.common.Users;
import net.java21.data2flow.sim.run.domain.RunAction;
import net.java21.data2flow.sim.run.service.OverviewService;
import net.java21.data2flow.sim.run.service.RunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** 실행 제어 내부 API(API-SIM-34, API-SIM-01·14~17의 simulator 쪽) */
@RestController
public class InternalRunController {

    private final RunService runs;
    private final OverviewService overview;

    public InternalRunController(RunService runs, OverviewService overview) {
        this.runs = runs;
        this.overview = overview;
    }

    @GetMapping("/internal/sim/overview")
    public ApiResponse<Map<String, Object>> overview(@OrgId long organizationId) {
        return ApiResponse.success(overview.overview(organizationId));
    }

    @PostMapping("/internal/sim/runs")
    public ResponseEntity<ApiResponse<RunService.StartResponse>> start(@OrgId long organizationId,
                                                                       @RequestBody RunService.StartRequest body) {
        RunService.StartResponse r = runs.start(organizationId, Users.currentOrSystem(), body);
        return ResponseEntity.created(URI.create("/internal/sim/runs/" + r.runId())).body(ApiResponse.success(r));
    }

    @GetMapping("/internal/sim/runs/{run-id}")
    public ApiResponse<Map<String, Object>> status(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.status(organizationId, runId));
    }

    public record AccelerationRequest(Integer acceleration) {
    }

    @PatchMapping("/internal/sim/runs/{run-id}")
    public ApiResponse<Map<String, Object>> acceleration(@OrgId long organizationId, @PathVariable("run-id") long runId,
                                                         @RequestBody AccelerationRequest body) {
        return ApiResponse.success(runs.changeAcceleration(organizationId, runId, body.acceleration()));
    }

    @PostMapping("/internal/sim/runs/{run-id}/start")
    public ApiResponse<RunService.ControlResponse> startAgain(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.control(organizationId, runId, RunAction.START));
    }

    @PostMapping("/internal/sim/runs/{run-id}/pause")
    public ApiResponse<RunService.ControlResponse> pause(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.control(organizationId, runId, RunAction.PAUSE));
    }

    @PostMapping("/internal/sim/runs/{run-id}/resume")
    public ApiResponse<RunService.ControlResponse> resume(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.control(organizationId, runId, RunAction.RESUME));
    }

    @PostMapping("/internal/sim/runs/{run-id}/stop")
    public ApiResponse<RunService.ControlResponse> stop(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.control(organizationId, runId, RunAction.STOP));
    }

    @PostMapping("/internal/sim/runs/{run-id}/reset")
    public ApiResponse<RunService.ControlResponse> reset(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.control(organizationId, runId, RunAction.RESET));
    }

    @GetMapping("/internal/sim/runs/{run-id}/report")
    public ApiResponse<Map<String, Object>> report(@OrgId long organizationId, @PathVariable("run-id") long runId) {
        return ApiResponse.success(runs.report(organizationId, runId));
    }

    public record RetainRequest(Instant retainUntil) {
    }

    @PatchMapping("/internal/sim/runs/{run-id}/report")
    public ApiResponse<Map<String, Object>> retain(@OrgId long organizationId, @PathVariable("run-id") long runId,
                                                   @RequestBody RetainRequest body) {
        return ApiResponse.success(runs.retain(organizationId, runId, body.retainUntil()));
    }
}
