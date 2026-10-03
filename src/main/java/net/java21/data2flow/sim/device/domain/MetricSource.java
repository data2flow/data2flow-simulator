package net.java21.data2flow.sim.device.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;

/**
 * 측정 항목 하나의 값 출처(SIM-02.02, {@code devices.metric_sources}).
 *
 * @param source    PHYSICS 또는 GENERATOR
 * @param generator GENERATOR일 때 생성기
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricSource(String source, GeneratorSpec generator) {
}
