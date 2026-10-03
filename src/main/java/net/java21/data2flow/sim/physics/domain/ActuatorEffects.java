package net.java21.data2flow.sim.physics.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 공간에 놓인 가상 장비들이 이번 틱에 물리 모델에 주는 영향의 합(SIM-03.03, SIM-09.04).
 * 장비 상태와 특성(냉방 능력·CADR·환기량 …)에서 {@code ActuatorEffectsCalculator}가 채운다.
 */
public final class ActuatorEffects {

    /** 냉난방 운전 방식 */
    public enum HvacMode { OFF, COOL, HEAT, DRY, FAN, AUTO }

    public HvacMode hvacMode = HvacMode.OFF;
    /** 냉방 능력 합(W, 풍량 반영) */
    public double coolingCapacityW;
    /** 난방 능력 합(W, 풍량 반영) */
    public double heatingCapacityW;
    /** 설정 온도 ℃(여러 대면 마지막 장비 값) */
    public double setpoint = 24;
    /** 고정 발열(W): 히터, 조명, 플러그 부하 등 */
    public double fixedHeatW;
    /** 기계 환기량 ㎥/h */
    public double ventilationM3h;
    /** 환기 열회수 효율 0~1 */
    public double heatRecovery;
    /** 공기청정기 CADR ㎥/h */
    public double cadrM3h;
    /** 가습량 g/h와 목표 습도 */
    public double humidifyGph;
    public double humidifyTarget = 100;
    /** 제습량 g/h와 목표 습도 */
    public double dehumidifyGph;
    public double dehumidifyTarget = 0;
    /** 조명 조도 기여 lux */
    public double lightLux;
    /** 창문 열림(장비) */
    public boolean windowOpen;
    /** 장비 소음(dB) */
    public final List<Double> noiseSourcesDb = new ArrayList<>();

    public boolean hvacOn() {
        return hvacMode != HvacMode.OFF;
    }
}
