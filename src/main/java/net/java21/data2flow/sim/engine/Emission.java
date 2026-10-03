package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.fault.domain.FaultSpec;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 시뮬레이션 한 틱이 밖으로 내보내는 것. 실행기가 스트림·이벤트로 발행한다 */
public sealed interface Emission {

    /**
     * 가상 센서·장비 원본 한 건 → {@code data2flow.raw}(RawEnvelope, EVT-ING-01).
     *
     * @param receivedAt 수신 시각(SIMULATED 정책이면 시뮬레이션 전송 시각)
     */
    record Uplink(long deviceId, long organizationId, long sourceId, String topic, byte[] payload, String dedupKey,
                  UUID messageId, Instant measuredAt, Instant receivedAt) implements Emission {
    }

    /** 명령 응답 → {@code device.command.ack}(EVT-SIM-03·EVT-ACT-06) */
    record CommandAck(String commandId, long deviceId, long organizationId, String result, String reason, Instant at)
            implements Emission {
    }

    /** 상태 보고 → {@code device.state.reported}(EVT-SIM-03·EVT-ACT-07) */
    record StateReported(long deviceId, long organizationId, long version, Map<String, Map<String, Object>> capabilities,
                         Instant at) implements Emission {
    }

    /** 장애 정답 라벨 → {@code sim.fault.started|ended}(EVT-SIM-02) */
    record FaultLabel(boolean started, FaultSpec fault, Instant at) implements Emission {
    }

    /** 실행 로그(SSE {@code sim.event}, API-SIM-16 lastEvents) */
    record Note(Instant simAt, String type, String message) implements Emission {
    }
}
