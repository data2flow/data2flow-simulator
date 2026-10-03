package net.java21.data2flow.sim.command.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceCommandAck;
import net.java21.data2flow.contracts.message.event.DeviceStateReported;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.actuator.domain.CommandInterpreter;
import net.java21.data2flow.sim.command.repository.CommandInboxRepository;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.device.domain.ReportMode;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.output.SimEventPublisher;
import net.java21.data2flow.sim.run.service.SimulationExecutor;
import net.java21.data2flow.sim.run.service.WorldFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가상 장비 명령(SIM-03.02·03.04, API-SIM-30·32). action의 virtual 드라이버가 부른다(ACT-03.02).
 *
 * <p>명령은 형식·범위를 바로 검사하고(400), 수신함에 넣은 뒤 202를 준다. 장비를 시뮬레이션하는 세계(실행·상시 환경)가 다음 틱에 가져가
 * 반응 지연·실패 확률을 적용하고 {@code device.command.ack}·{@code device.state.reported}를 낸다. 돌고 있는 세계가 없는 장비(RUN_ONLY이고
 * 실행 중이 아님)는 여기서 바로 적용하고 응답한다.
 */
@Service
public class CommandService {

    private final SimDeviceRepository devices;
    private final CommandInboxRepository inbox;
    private final WorldFactory factory;
    private final SimulationExecutor executor;
    private final SimEventPublisher events;
    private final Clock clock;

    public CommandService(SimDeviceRepository devices, CommandInboxRepository inbox, WorldFactory factory, SimulationExecutor executor,
                          SimEventPublisher events, Clock clock) {
        this.devices = devices;
        this.inbox = inbox;
        this.factory = factory;
        this.executor = executor;
        this.events = events;
        this.clock = clock;
    }

    /**
     * API-SIM-30 요청(action virtual 드라이버의 {@code DriverCommand}에서 옮김).
     *
     * @param commandId      명령 ID(ACT {@code commands.id}). 같은 ID 재전송은 한 번만 적용
     * @param capability     표준 기능(Switch, Thermostat …)
     * @param command        명령({@code set} 또는 별칭)
     * @param args           인자
     * @param desiredVersion ACT desired 버전(기록용)
     */
    public record CommandRequest(String commandId, String capability, String command, Map<String, Object> args, Long desiredVersion) {
    }

    @Transactional
    public Map<String, Object> submit(long deviceId, CommandRequest req) {
        if (req.commandId() == null || req.commandId().isBlank() || req.commandId().length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("commandId", "Size", "1~64자")));
        }
        SimDeviceRepository.DeviceRow row = devices.loadById(deviceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        WorldDevice device = factory.device(row);
        if (!device.actuator()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("capability", "UNSUPPORTED_CAPABILITY", "센서는 명령을 받지 않습니다")));
        }
        ActuatorState current = executor.actuatorState(deviceId).orElseGet(() -> stored(device));
        String command = req.command() == null ? "set" : req.command();
        ActuatorState next = CommandInterpreter.apply(device.type().key(), device.properties(), current, req.capability(), command, req.args());
        Instant now = clock.instant();
        boolean live = executor.simulatedSomewhere(row.organizationId(), deviceId, row.reportMode() == ReportMode.ALWAYS, row.spaceId());
        if (live) {
            inbox.insert(row.organizationId(), deviceId, req.commandId(), req.capability(), command, req.args(), req.desiredVersion(), now);
        } else {
            // 돌고 있는 세계가 없다: 지금 적용하고 응답한다(물리 피드백은 다음 실행에서)
            next.version = current.version + 1;
            devices.saveRuntime(deviceId, row.frameCounter(), null, next);
            events.publish(EventType.DEVICE_COMMAND_ACK, row.organizationId(),
                    DeviceCommandAck.acked(req.commandId(), deviceId, now, true));
            events.publish(EventType.DEVICE_STATE_REPORTED, row.organizationId(),
                    new DeviceStateReported(deviceId, next.version, next.reported(), now, true));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("accepted", true);
        r.put("commandId", req.commandId());
        r.put("organizationId", Long.toString(row.organizationId()));
        r.put("applied", !live);
        return r;
    }

    private static ActuatorState stored(WorldDevice d) {
        return d.actuatorState() != null ? d.actuatorState() : ActuatorState.initial(d.type().capabilities());
    }

    /** API-SIM-32: 현재 reported 상태(드라이버 {@code getState()}) */
    public Map<String, Object> state(long deviceId) {
        SimDeviceRepository.DeviceRow row = devices.loadById(deviceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        WorldDevice device = factory.device(row);
        if (!device.actuator()) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        ActuatorState s = executor.actuatorState(deviceId).orElseGet(() -> stored(device));
        Map<String, Map<String, Object>> caps = s.reported();
        if (caps.containsKey("Thermostat")) {
            executor.spaceState(row.organizationId(), row.spaceId())
                    .ifPresent(sp -> caps.get("Thermostat").put("currentTemperature", Math.round(sp.temperature * 10) / 10.0));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("deviceId", Long.toString(deviceId));
        r.put("organizationId", Long.toString(row.organizationId()));
        r.put("externalId", row.externalId());
        r.put("capabilities", caps);
        r.put("version", s.version);
        r.put("reportedAt", clock.instant());
        r.put("virtual", true);
        return r;
    }
}
