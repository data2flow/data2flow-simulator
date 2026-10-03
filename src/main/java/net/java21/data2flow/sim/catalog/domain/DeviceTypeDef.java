package net.java21.data2flow.sim.catalog.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.sim.device.domain.PayloadFormat;

import java.util.List;
import java.util.Optional;

/**
 * 가상 기기 유형(SIM-09.01, {@code device_types}). 플랫폼 기본 유형은 {@link BuiltinCatalog}에 있고 시작할 때 DB에 맞춘다.
 *
 * @param key                   유형 키(예: {@code th-sensor}, {@code aircon})
 * @param name                  이름
 * @param category              SENSOR, ACTUATOR
 * @param metrics               센서 측정 항목(장비는 보고 항목 power·energy 등)
 * @param capabilities          장비 표준 기능(ACT-01.02). 센서는 빈 목록
 * @param propertyDefs          특성 정의
 * @param physicsEffects        물리 영향
 * @param linkedModelCode       연결한 실제 기기 모델(DEV-03, SIM-09.08). 없으면 null
 * @param defaultPayloadFormat  기본 payload 형식
 * @param defaultReportIntervalSec 기본 보고 주기(초)
 * @param reportOnChange        값이 바뀔 때 즉시 보고(문 열림 센서). 주기 보고는 하트비트
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceTypeDef(String key, String name, String category, List<MetricDef> metrics, List<String> capabilities,
                            List<PropertyDef> propertyDefs, List<PhysicsEffect> physicsEffects, String linkedModelCode,
                            PayloadFormat defaultPayloadFormat, int defaultReportIntervalSec, boolean reportOnChange) {

    public static final String SENSOR = "SENSOR";
    public static final String ACTUATOR = "ACTUATOR";

    public DeviceTypeDef {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        propertyDefs = propertyDefs == null ? List.of() : List.copyOf(propertyDefs);
        physicsEffects = physicsEffects == null ? List.of() : List.copyOf(physicsEffects);
    }

    @JsonIgnore
    public boolean actuator() {
        return ACTUATOR.equals(category);
    }

    public Optional<PropertyDef> property(String key) {
        return propertyDefs.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    public Optional<MetricDef> metric(String key) {
        return metrics.stream().filter(m -> m.key().equals(key)).findFirst();
    }
}
