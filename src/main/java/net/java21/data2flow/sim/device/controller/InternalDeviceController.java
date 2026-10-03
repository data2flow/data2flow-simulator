package net.java21.data2flow.sim.device.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.sim.common.OrgId;
import net.java21.data2flow.sim.device.service.DeviceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** 가상 기기 설정 내부 API(API-SIM-33, API-SIM-05·06·07·09의 simulator 쪽) */
@RestController
public class InternalDeviceController {

    private final DeviceService devices;

    public InternalDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/internal/sim/devices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@OrgId long organizationId,
                                                                   @RequestBody DeviceService.CreateRequest body) {
        Map<String, Object> d = devices.create(organizationId, body);
        return ResponseEntity.created(URI.create("/internal/sim/devices/" + d.get("deviceId"))).body(ApiResponse.success(d));
    }

    public record BatchRequest(List<DeviceService.CreateRequest> devices) {
    }

    /** 여러 대 한 번에(API-SIM-05 count, 지점 자동 배치는 core). 한도를 넘으면 한 대도 만들지 않는다 */
    @PostMapping("/internal/sim/devices/batch-create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batch(@OrgId long organizationId, @RequestBody BatchRequest body) {
        List<Map<String, Object>> created = devices.createAll(organizationId, body.devices() == null ? List.of() : body.devices());
        return ResponseEntity.status(201).body(ApiResponse.success(Map.of("devices", created)));
    }

    @GetMapping("/internal/sim/devices/{device-id}")
    public ApiResponse<Map<String, Object>> get(@OrgId long organizationId, @PathVariable("device-id") long deviceId) {
        return ApiResponse.success(devices.get(organizationId, deviceId));
    }

    @PatchMapping("/internal/sim/devices/{device-id}")
    public ApiResponse<Map<String, Object>> patch(@OrgId long organizationId, @PathVariable("device-id") long deviceId,
                                                  @RequestBody JsonNode body) {
        return ApiResponse.success(devices.patch(organizationId, deviceId, body));
    }

    @DeleteMapping("/internal/sim/devices/{device-id}")
    public ResponseEntity<Void> delete(@OrgId long organizationId, @PathVariable("device-id") long deviceId) {
        devices.delete(organizationId, deviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/sim/kits/{kit-key}/place")
    public ResponseEntity<ApiResponse<Map<String, Object>>> placeKit(@OrgId long organizationId, @PathVariable("kit-key") String kitKey,
                                                                     @RequestBody DeviceService.KitRequest body) {
        return ResponseEntity.status(201).body(ApiResponse.success(devices.placeKit(organizationId, kitKey, body)));
    }
}
