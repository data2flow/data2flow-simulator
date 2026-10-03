package net.java21.data2flow.sim.support;

/** 물리 모델 허용 오차(SIM test-plan "물리 모델 허용 오차"). 모든 물리 테스트가 함께 쓴다 */
public final class PhysicsTolerances {

    /** 냉방 능력 2배 → 목표 도달 시간 비 0.5 ± 20% */
    public static final double CAPACITY_TIME_RATIO_MIN = 0.4;
    public static final double CAPACITY_TIME_RATIO_MAX = 0.6;
    /** CADR 2배 → 감소 속도 비 2.0 ± 20% */
    public static final double CADR_RATE_RATIO_MIN = 1.6;
    public static final double CADR_RATE_RATIO_MAX = 2.4;
    /** 에어컨 ON 안정 온도 = 설정 ± 1.0℃, 안정 = 10분 이동평균 변화 < 0.1℃/10분 */
    public static final double SETPOINT_BAND = 1.0;
    public static final double STABLE_CHANGE_PER_10MIN = 0.1;
    /** 강의실 30명·환기 OFF → 60분 안에 1,000ppm 초과 */
    public static final double CO2_LIMIT_MINUTES = 60;
    /** 외기 기준 CO2 420 ± 30ppm */
    public static final double OUTDOOR_CO2 = 420;
    public static final double OUTDOOR_CO2_BAND = 30;
    /** 무인 소음 30 ± 3dB */
    public static final double QUIET_NOISE = 30;
    public static final double QUIET_NOISE_BAND = 3;
    /** 에너지 ± 2% */
    public static final double ENERGY_REL = 0.02;
    /** 보고 지터: 주기 60s, 10% → 54~66s */
    public static final long JITTER_MIN_MS = 54_000;
    public static final long JITTER_MAX_MS = 66_000;

    private PhysicsTolerances() {
    }
}
