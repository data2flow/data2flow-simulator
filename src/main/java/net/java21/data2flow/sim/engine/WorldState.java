package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.physics.domain.SpaceState;
import net.java21.data2flow.sim.sensor.domain.GeneratorState;

import java.util.ArrayList;
import java.util.TreeMap;

/**
 * 시뮬레이션의 바뀌는 상태 전부. 체크포인트(10틱마다 {@code runs.checkpoint})에 JSON으로 저장하고, 인스턴스가 바뀌면 여기서 이어 실행한다.
 * 난수는 상태 없이 (시드, 키, 순번)으로 뽑으므로 여기에 난수 생성기 상태는 없다.
 */
public final class WorldState {

    /** 끝난 틱 수 */
    public long tick;
    public TreeMap<Long, SpaceState> spaces = new TreeMap<>();
    /** 센서 응답 지연용 공간 상태 이력(틱 끝마다, 최근 {@link SimulationWorld#HISTORY_TICKS}개) */
    public TreeMap<Long, ArrayList<SpaceSample>> history = new TreeMap<>();
    public TreeMap<Long, SensorRuntime> sensors = new TreeMap<>();
    public TreeMap<Long, ActuatorRuntime> actuators = new TreeMap<>();
    /** 장애(시나리오 예약분 + 실행 중 주입분) */
    public ArrayList<FaultSpec> faults = new ArrayList<>();
    public TreeMap<Long, FaultRuntime> faultRuntime = new TreeMap<>();
    /** 지연·순서 뒤바뀜 장애로 붙잡아 둔 원본 */
    public ArrayList<PendingUplink> pending = new ArrayList<>();
    /** 아직 처리하지 않은 명령 */
    public ArrayList<InboundCommand> inbound = new ArrayList<>();
    /** 다음에 실행할 시나리오 동작 번호 */
    public int nextAction;
    /** 생성 값 연쇄 해시(SHA-256 hex) */
    public String digest = "";
    public long readingCount;
    public long uplinkCount;
    public Stats stats = new Stats();
    public TreeMap<String, ExpectationProgress> expectations = new TreeMap<>();

    public WorldState() {
    }

    /** 공간 상태 표본 */
    public static final class SpaceSample {
        public long atMs;
        public SpaceState state;

        public SpaceSample() {
        }

        public SpaceSample(long atMs, SpaceState state) {
            this.atMs = atMs;
            this.state = state;
        }
    }

    /** 센서 런타임 상태 */
    public static final class SensorRuntime {
        public long nextReportAtMs;
        public long reportIndex;
        public long frameCounter;
        public double battery = 100;
        public TreeMap<String, Double> lastValues = new TreeMap<>();
        public TreeMap<String, GeneratorState> generators = new TreeMap<>();
        /** 변화 보고 센서(문)의 마지막 보고 값 */
        public Double lastChangeValue;
        /** PIR 마지막 감지 시각 */
        public long lastDetectionMs = Long.MIN_VALUE / 2;
    }

    /** 장비 런타임 상태 */
    public static final class ActuatorRuntime {
        /** 보고 상태(명령 적용 즉시) */
        public ActuatorState reported;
        /** 물리 모델이 쓰는 유효 상태(반응 지연 뒤) */
        public ActuatorState effective;
        public ArrayList<PendingEffect> pendingEffects = new ArrayList<>();
        public double energyKwh;
        public double lastPowerW;
        public long nextReportAtMs;
        public long reportIndex;
        public long frameCounter;
        /** commandId → 시도 횟수(실패 확률 재시도 판정) */
        public TreeMap<String, Integer> commandAttempts = new TreeMap<>();
        /** commandId → 결과(ACKED·FAILED). 같은 commandId 재전송은 다시 적용하지 않고 같은 응답을 준다 */
        public TreeMap<String, String> commandResults = new TreeMap<>();
        public long controlCount;
    }

    /** 반응 지연 뒤 유효 상태로 바뀔 예정 */
    public static final class PendingEffect {
        public long atMs;
        public ActuatorState state;

        public PendingEffect() {
        }

        public PendingEffect(long atMs, ActuatorState state) {
            this.atMs = atMs;
            this.state = state;
        }
    }

    /** 장애 런타임 */
    public static final class FaultRuntime {
        public int spikesUsed;
        public long lastSpikeAtMs = Long.MIN_VALUE;
        public TreeMap<String, Double> stuck = new TreeMap<>();
        public boolean started;
        public boolean ended;
        public ArrayList<PendingUplink> reorderBuffer = new ArrayList<>();
        public long reorderStartMs;
    }

    /** 붙잡아 둔 원본 */
    public static final class PendingUplink {
        public long deviceId;
        public long sourceId;
        public String topic;
        public byte[] payload;
        public String dedupKey;
        public String messageId;
        public long measuredAtMs;
        public long sendAtMs;

        public PendingUplink() {
        }
    }

    /** 들어온 명령(API-SIM-30 또는 시나리오 ACTUATOR 트랙) */
    public static final class InboundCommand {
        public String commandId;
        public long deviceId;
        public String capability;
        public String command;
        public TreeMap<String, Object> args = new TreeMap<>();
        /** API, SCENARIO */
        public String source;
        public long receivedAtMs;

        public InboundCommand() {
        }
    }

    /** 실행 지표(API-SIM-17 metrics) */
    public static final class Stats {
        public double energyKwh;
        public long controlCount;
        /** 목표 범위(22~26℃, CO2 1,000ppm 이하) 안에 있던 공간·초 */
        public long comfortSec;
        public long outOfTargetSec;
        public long faultsStarted;
    }

    /** 기대 결과 진행 상황 */
    public static final class ExpectationProgress {
        /** PENDING, PASSED, FAILED, SKIPPED */
        public String state = "PENDING";
        public Long atMs;
        public double inRange;
        public double total;
        public Double value;
    }
}
