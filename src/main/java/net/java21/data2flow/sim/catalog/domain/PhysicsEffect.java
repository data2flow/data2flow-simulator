package net.java21.data2flow.sim.catalog.domain;

/**
 * 장비 특성이 물리 모델에 주는 영향(SIM-09.04, {@code device_types.physics_effects[]}).
 *
 * @param effect      COOLING, HEATING, VENTILATION, PURIFY, HUMIDIFY, DEHUMIDIFY, LIGHT, POWER
 * @param propertyKey 영향 크기를 정하는 특성 키
 */
public record PhysicsEffect(String effect, String propertyKey) {
}
