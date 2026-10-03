package net.java21.data2flow.sim.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * simulator 설정({@code data2flow.sim.*}). 비밀값은 환경변수(.env / k8s Secret)로만 받는다.
 *
 * @param env              배포 환경 dev·stg·prod·test. prod·stg에서는 출력 경로 PLATFORM_MQTT를 거부한다(SIM-02.07)
 * @param instanceId       인스턴스 이름(파드 이름). 실행 소유(leases)에 쓴다
 * @param flywayMode       migrate(staging·시험) 또는 validate(prod·로컬, ADR-030)
 * @param organizationIds  이 배포가 시뮬레이션할 조직. 비우면 core 배포 조직(API-DSC-50의 SIMULATION 소스 조직)만(ADR-030)
 * @param sourceIds        조직 → SIM 데이터 소스 ID(설정하면 core 조회보다 우선)
 * @param coreUri          core-api 내부 주소
 * @param zone             조직 시간대(일주기·시간표 기준)
 * @param tickSec          시뮬레이션 틱(초)
 */
@ConfigurationProperties("data2flow.sim")
public record SimProperties(
        @DefaultValue("dev") String env,
        @DefaultValue("simulator-local-0") String instanceId,
        @DefaultValue("validate") String flywayMode,
        List<Long> organizationIds,
        Map<Long, Long> sourceIds,
        @DefaultValue("http://data2flow-core-api") String coreUri,
        @DefaultValue("60s") Duration directoryRefresh,
        @DefaultValue("Asia/Seoul") String zone,
        @DefaultValue("10") int tickSec,
        @DefaultValue Executor executor,
        @DefaultValue Limits limits,
        @DefaultValue Output output,
        @DefaultValue Stream stream,
        @DefaultValue Heartbeat heartbeat,
        @DefaultValue Retention retention) {

    public SimProperties {
        organizationIds = organizationIds == null ? List.of() : List.copyOf(organizationIds);
        sourceIds = sourceIds == null ? Map.of() : Map.copyOf(sourceIds);
    }

    /** 운영·스테이징인가(PLATFORM_MQTT 금지, SIM-02.07) */
    public boolean deployed() {
        return "prod".equals(env) || "stg".equals(env) || "staging".equals(env);
    }

    /**
     * @param enabled  실행기(시나리오 실행·상시 환경)를 돌린다
     * @param period   실행기 주기(실제 시간)
     * @param lease    실행 소유 기한. 이만큼 갱신이 없으면 다른 인스턴스가 넘겨받는다
     * @param ambient  상시 보고(ALWAYS) 기기를 실제 시간으로 돌린다
     * @param maxTicksPerRound 한 주기에 진행할 최대 틱 수(밀린 실행 따라잡기 상한)
     */
    public record Executor(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration period,
                           @DefaultValue("30s") Duration lease, @DefaultValue("true") boolean ambient,
                           @DefaultValue("600") int maxTicksPerRound) {
    }

    /**
     * 한도(SIM-11.01·11.02, BR-SIM-08·09).
     *
     * @param devicesPerOrganization 조직당 가상 기기
     * @param concurrentRuns         조직당 동시 실행(RUNNING·PAUSED·EVALUATING)
     * @param devicesAtMaxAcceleration x60에서 허용하는 기기 수
     * @param designIngestPerSec     설계 수집 처리량(NFR-01.02)
     * @param virtualShare           가상 메시지 상한 비율(설계 처리량의 50%)
     */
    public record Limits(@DefaultValue("500") int devicesPerOrganization, @DefaultValue("5") int concurrentRuns,
                         @DefaultValue("100") int devicesAtMaxAcceleration, @DefaultValue("200") int designIngestPerSec,
                         @DefaultValue("0.5") double virtualShare) {

        public double virtualMessagesPerSec() {
            return designIngestPerSec * virtualShare;
        }
    }

    /**
     * @param mode STREAM(data2flow.raw 직접 주입) 또는 MEMORY(시험)
     */
    public record Output(@DefaultValue("STREAM") String mode, @DefaultValue("true") boolean events) {
    }

    /** RabbitMQ Stream(data2flow.raw). 운영은 10.116.64.14:5552(내부망 전용) */
    public record Stream(@DefaultValue("localhost") String host, @DefaultValue("5552") int port,
                         @DefaultValue("data2flow-dev") String virtualHost, @DefaultValue("guest") String username,
                         @DefaultValue("guest") String password, @DefaultValue("false") boolean useConfiguredAddress,
                         @DefaultValue("true") boolean createSuperStream, @DefaultValue("10s") Duration confirmTimeout,
                         @DefaultValue("10000") int maxUnconfirmed) {
    }

    /**
     * 전 구간 하트비트 카나리(ING-07.05, reliability-and-ha.md §6).
     *
     * @param enabled        켜기
     * @param organizationId 시스템 가상 기기의 조직(비우면 배포 조직 중 가장 작은 ID)
     * @param sourceId       그 조직의 SIM 소스(비우면 조회)
     * @param externalId     시스템 가상 기기 외부 ID
     * @param interval       주기(10초)
     */
    public record Heartbeat(@DefaultValue("false") boolean enabled, Long organizationId, Long sourceId,
                            @DefaultValue("__heartbeat__") String externalId, @DefaultValue("10s") Duration interval) {
    }

    /**
     * 보관(SIM-11.03, BR-SIM-14).
     *
     * @param days     끝난 실행 보관 일수
     * @param maxDays  연장 상한
     * @param enabled  정리 작업(만료 실행 PURGED 표시 + core 정리 요청)
     */
    public record Retention(@DefaultValue("30") int days, @DefaultValue("365") int maxDays, @DefaultValue("true") boolean enabled) {
    }
}
