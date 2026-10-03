package net.java21.data2flow.sim.fault.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.fault.domain.FaultValidator;
import net.java21.data2flow.sim.fault.repository.FaultRepository;
import net.java21.data2flow.sim.run.domain.RunStatus;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.run.service.SimulationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장애 주입·해제(SIM-05.01~05.04, API-SIM-20·21). 가상 기기·가상 게이트웨이에만 넣고(BR-SIM-15), 넣은 장애가 그대로 정답 라벨이다.
 * 시나리오 이벤트로 넣은 장애와 실행 중 즉시 주입한 장애는 같은 엔진 경로를 지나므로 결과가 같다(TC-SIM-065).
 */
@Service
public class FaultService {

    private final FaultRepository faults;
    private final SimDeviceRepository devices;
    private final RunRepository runs;
    private final SimulationExecutor executor;
    private final Clock clock;

    public FaultService(FaultRepository faults, SimDeviceRepository devices, RunRepository runs, SimulationExecutor executor, Clock clock) {
        this.faults = faults;
        this.devices = devices;
        this.runs = runs;
        this.executor = executor;
        this.clock = clock;
    }

    /** API-SIM-20 요청 */
    public record InjectRequest(Long runId, String targetType, List<String> targetIds, String kind, Map<String, Object> params,
                                Long startInSec, Long durationSec) {
    }

    @Transactional
    public Map<String, Object> inject(long organizationId, InjectRequest req) {
        FaultKind kind;
        try {
            kind = FaultKind.valueOf(String.valueOf(req.kind()));
        } catch (IllegalArgumentException e) {
            throw invalid("kind", "STUCK|SPIKE|DRIFT|DROPOUT|INTERMITTENT|BATTERY_DRAIN|OUT_OF_RANGE|GATEWAY_DOWN|DUPLICATE|REORDER|DELAY|MALFORMED");
        }
        String targetType = req.targetType() == null ? FaultSpec.DEVICE : req.targetType();
        if (!FaultSpec.DEVICE.equals(targetType) && !FaultSpec.GATEWAY.equals(targetType)) {
            throw invalid("targetType", "DEVICE|GATEWAY");
        }
        if (req.targetIds() == null || req.targetIds().isEmpty()) {
            throw invalid("targetIds", "1개 이상");
        }
        long duration = req.durationSec() == null ? 1800 : req.durationSec();
        long startIn = req.startInSec() == null ? 0 : req.startInSec();
        if (startIn < 0) {
            throw invalid("startInSec", "0 이상");
        }
        Map<String, Object> params = req.params() == null ? Map.of() : req.params();
        FaultValidator.validate(kind, targetType, params, duration);
        guardTargets(organizationId, targetType, req.targetIds());
        Instant base;
        if (req.runId() != null) {
            RunRepository.RunRow run = runs.findById(organizationId, req.runId())
                    .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
            if (run.status() != RunStatus.RUNNING && run.status() != RunStatus.PAUSED) {
                throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
            }
            base = executor.simNow(req.runId()).orElse(run.simClock());
        } else {
            base = clock.instant();   // 상시 환경은 시뮬레이션 시각 = 실제 시각
        }
        List<String> ids = new ArrayList<>();
        for (String target : req.targetIds()) {
            Instant from = base.plusSeconds(startIn);
            FaultSpec spec = new FaultSpec(0, kind, targetType, target.toLowerCase(), params, from, from.plus(Duration.ofSeconds(duration)));
            long id = faults.insert(organizationId, req.runId(), spec);
            executor.addFault(organizationId, req.runId(), new FaultSpec(id, kind, targetType, spec.targetId(), params, spec.simFrom(),
                    spec.simTo()));
            ids.add(Long.toString(id));
        }
        return Map.of("faultIds", ids);
    }

    /** 가상 기기·가상 게이트웨이만(BR-SIM-15). 시뮬레이터에 없는 기기 = 실제 기기 → SIM_TARGET_NOT_VIRTUAL */
    private void guardTargets(long organizationId, String targetType, List<String> targets) {
        List<SimDeviceRepository.DeviceRow> mine = devices.findAll(organizationId);
        Set<String> deviceIds = new HashSet<>();
        Set<String> gateways = new HashSet<>();
        gateways.add(SimulationWorld.defaultGateway(organizationId));
        for (SimDeviceRepository.DeviceRow d : mine) {
            deviceIds.add(Long.toString(d.deviceId()));
            if (d.gatewayEui() != null) {
                gateways.add(d.gatewayEui().toLowerCase());
            }
        }
        for (String t : targets) {
            boolean ok = FaultSpec.DEVICE.equals(targetType) ? deviceIds.contains(t) : gateways.contains(t.toLowerCase());
            if (!ok) {
                throw new BusinessException(SimErrorCode.SIM_TARGET_NOT_VIRTUAL, List.of(new FieldErrorDetail("targetIds",
                        SimErrorCode.SIM_TARGET_NOT_VIRTUAL.code(), "가상 기기·가상 게이트웨이가 아닙니다: " + t)));
            }
        }
    }

    /** API-SIM-21 해제: 정답 라벨의 끝을 해제 시각으로 고정 */
    @Transactional
    public Map<String, Object> cancel(long organizationId, long faultId) {
        FaultRepository.FaultRow row = faults.findById(organizationId, faultId)
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if ("ENDED".equals(row.status()) || "CANCELLED".equals(row.status())) {
            throw new BusinessException(SimErrorCode.SIM_RUN_STATE_CONFLICT);
        }
        Instant at = executor.cancelFault(organizationId, row.runId(), faultId)
                .orElseGet(() -> row.runId() == null ? clock.instant() : row.spec().simFrom());
        Instant end = at.isBefore(row.spec().simFrom()) ? row.spec().simFrom() : at;
        if (row.spec().simTo() != null && end.isAfter(row.spec().simTo())) {
            end = row.spec().simTo();
        }
        faults.updateStatus(organizationId, faultId, "CANCELLED", end);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("faultId", Long.toString(faultId));
        r.put("kind", row.spec().kind().name());
        r.put("status", "CANCELLED");
        r.put("simFrom", row.spec().simFrom());
        r.put("simTo", end);
        return r;
    }

    public ListApiResponse<Map<String, Object>> list(long organizationId, Long runId, String status, Integer page, Integer size) {
        PageParams p = PageParams.of(page, size);
        Instant now = clock.instant();
        List<Map<String, Object>> rows = faults.list(organizationId, runId, status, p.size(), p.offset()).stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("faultId", Long.toString(f.id()));
            m.put("runId", f.runId() == null ? null : Long.toString(f.runId()));
            m.put("kind", f.spec().kind().name());
            m.put("targetType", f.spec().targetType());
            m.put("targetId", f.spec().targetId());
            m.put("params", f.spec().params());
            m.put("simFrom", f.spec().simFrom());
            m.put("simTo", f.spec().simTo());
            m.put("status", f.status());
            Instant simNow = f.runId() == null ? now : executor.simNow(f.runId()).orElse(null);
            m.put("remainingSec", simNow == null || f.spec().simTo() == null ? null
                    : Math.max(0, Duration.between(simNow, f.spec().simTo()).toSeconds()));
            return m;
        }).toList();
        return ListApiResponse.of(p, rows, faults.count(organizationId, runId, status));
    }

    private static BusinessException invalid(String field, String allowed) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Invalid", allowed)));
    }
}
