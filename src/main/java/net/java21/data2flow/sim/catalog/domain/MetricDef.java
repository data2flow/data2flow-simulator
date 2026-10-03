package net.java21.data2flow.sim.catalog.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;

/**
 * 가상 센서 유형의 측정 항목(SIM-02.01, {@code device_types.metrics[]}).
 *
 * @param key           표준 측정 키(ChirpStack {@code object} 키 그대로, DEV-03)
 * @param unit          단위. 없으면 null
 * @param defaultSource PHYSICS(공간 물리 모델) 또는 GENERATOR(독립 생성기)
 * @param generator     GENERATOR일 때 기본 생성기. PHYSICS면 null
 * @param resolution    분해능(소수 자릿수 반올림 단위)
 * @param validMin      센서 측정 범위 최솟값(OUT_OF_RANGE 장애 기본값 계산)
 * @param validMax      센서 측정 범위 최댓값
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricDef(String key, String unit, String defaultSource, GeneratorSpec generator, double resolution,
                        Double validMin, Double validMax) {

    public static final String PHYSICS = "PHYSICS";
    public static final String GENERATOR = "GENERATOR";
    /** 배터리 모델(SIM-02.05)·장비 전력처럼 시뮬레이터가 따로 계산하는 값 */
    public static final String DERIVED = "DERIVED";

    public static MetricDef physics(String key, String unit, double resolution, Double min, Double max) {
        return new MetricDef(key, unit, PHYSICS, null, resolution, min, max);
    }

    public static MetricDef generated(String key, String unit, GeneratorSpec generator, double resolution, Double min, Double max) {
        return new MetricDef(key, unit, GENERATOR, generator, resolution, min, max);
    }

    public static MetricDef derived(String key, String unit, double resolution, Double min, Double max) {
        return new MetricDef(key, unit, DERIVED, null, resolution, min, max);
    }
}
