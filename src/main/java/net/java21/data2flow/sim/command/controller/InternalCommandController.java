package net.java21.data2flow.sim.command.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.sim.command.service.CommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * action virtual 드라이버가 부르는 내부 API(API-SIM-30·32). 기기 ID는 전역 고유라 조직 헤더 없이 받는다(응답에 조직을 담음).
 */
@RestController
public class InternalCommandController {

    private final CommandService commands;

    public InternalCommandController(CommandService commands) {
        this.commands = commands;
    }

    /** 202 {accepted: true}. 반응 지연 뒤 상태 반영, EVT-SIM-03(ack·reported). 실패 확률에 걸리면 ack를 보내지 않는다 */
    @PostMapping("/internal/sim/devices/{device-id}/commands")
    public ResponseEntity<ApiResponse<Map<String, Object>>> command(@PathVariable("device-id") long deviceId,
                                                                    @RequestBody CommandService.CommandRequest body) {
        return ResponseEntity.accepted().body(ApiResponse.success(commands.submit(deviceId, body)));
    }

    @GetMapping("/internal/sim/devices/{device-id}/state")
    public ApiResponse<Map<String, Object>> state(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(commands.state(deviceId));
    }
}
