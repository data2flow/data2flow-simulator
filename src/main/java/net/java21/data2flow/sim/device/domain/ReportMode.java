package net.java21.data2flow.sim.device.domain;

/** 보고 방식: ALWAYS(배치하면 상시 보고, 시나리오 실행 중에는 그 실행이 맡음), RUN_ONLY(시나리오 실행 중에만) */
public enum ReportMode {
    ALWAYS, RUN_ONLY
}
