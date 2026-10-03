package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.device.domain.ResponseSettings;

import java.util.Map;
import java.util.TreeMap;

/**
 * 시뮬레이션에 넣는 가상 기기(유효 특성까지 푼 값).
 *
 * @param deviceId        core 기기 ID
 * @param organizationId  조직
 * @param name            이름(난수 스트림 키: 기기 추가 순서·ID가 바뀌어도 값이 같게, TC-SIM-087·088)
 * @param externalId      외부 ID(devEui)
 * @param sourceId        SIM 데이터 소스 ID
 * @param spaceId         공간
 * @param type            유형
 * @param properties      유효 특성(카탈로그 → 프로필 → 기기)
 * @param metricSources   측정 항목별 출처(없으면 유형 기본값)
 * @param reportIntervalSec 보고 주기
 * @param jitterPct       지터 %
 * @param batteryDrainPerReport 보고당 배터리 소모(%p). null이면 배터리 수명 특성으로 계산
 * @param payloadFormat   payload 형식
 * @param gatewayEui      가상 게이트웨이
 * @param response        장비 응답 특성
 * @param seed            기기 시드(없으면 실행 시드)
 * @param frameCounter    시작 fCnt
 * @param batteryPct      시작 배터리
 * @param actuatorState   장비 시작 상태(없으면 OFF)
 */
public record WorldDevice(long deviceId, long organizationId, String name, String externalId, long sourceId, long spaceId,
                          DeviceTypeDef type, Map<String, Object> properties, Map<String, MetricSource> metricSources,
                          int reportIntervalSec, double jitterPct, Double batteryDrainPerReport, PayloadFormat payloadFormat,
                          String gatewayEui, ResponseSettings response, Long seed, long frameCounter, Double batteryPct,
                          ActuatorState actuatorState) {

    public WorldDevice {
        properties = properties == null ? Map.of() : new TreeMap<>(properties);
        metricSources = metricSources == null ? Map.of() : new TreeMap<>(metricSources);
        response = response == null ? ResponseSettings.DEFAULT : response;
    }

    public boolean actuator() {
        return type.actuator();
    }

    public double property(String key, double fallback) {
        Object v = properties.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public boolean flag(String key) {
        return Boolean.TRUE.equals(properties.get(key));
    }

    /** 이 기기 난수에 쓰는 시드 */
    public long seedOr(long runSeed) {
        return seed == null ? runSeed : seed;
    }

    /** 반응 지연(초): 응답 설정 → 유형 특성 */
    public int reactionDelaySec() {
        if (response.reactionDelaySec() != null) {
            return response.reactionDelaySec();
        }
        return (int) property("reactionDelaySec", 0);
    }

    public String profileName() {
        return type.linkedModelCode() != null ? type.linkedModelCode() : type.key();
    }
}
