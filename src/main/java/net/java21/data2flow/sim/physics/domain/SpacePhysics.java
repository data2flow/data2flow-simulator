package net.java21.data2flow.sim.physics.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 가상 공간 물리 파라미터(SIM-01.02, erd/sim.md {@code space_physics}, API-SIM-10 {@code physics}).
 *
 * @param preset             CLASSROOM, OFFICE, MEETING, CUSTOM
 * @param areaM2             면적 1~5000㎡
 * @param heightM            층고 2~20m(부피 = 면적 × 층고)
 * @param uValue             외피 열관류율 0.1~6.0 W/㎡K
 * @param envelopeM2         외기에 닿는 외피 면적(㎡)
 * @param windowM2           창 면적(일사·채광). 없으면 0
 * @param windowOrientation  창 방향 N·E·S·W(일사 계수). 없으면 S
 * @param solarGainFactor    일사 취득 계수 0~1
 * @param initialState       초기 상태
 * @param outdoorLinked      외기와 공기가 오가는가(침기). false면 침기 0.05회/h
 * @param outdoorCo2Ppm      외기 CO2(기본 420ppm)
 * @param perPerson          1인당 발열·CO2·수분·소음
 * @param backgroundNoiseDb  배경 소음(기본 30dB)
 * @param noiseStd           측정 항목별 기본 잡음 표준편차(센서 특성이 없을 때)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpacePhysics(
        SpacePreset preset,
        double areaM2,
        double heightM,
        double uValue,
        double envelopeM2,
        Double windowM2,
        String windowOrientation,
        double solarGainFactor,
        InitialState initialState,
        boolean outdoorLinked,
        double outdoorCo2Ppm,
        PerPerson perPerson,
        double backgroundNoiseDb,
        Map<String, Double> noiseStd) {

    /** 침기(틈새 환기) 회/h. 외기 연결이 없으면 거의 0 */
    public static final double INFILTRATION_ACH = 0.3;
    public static final double SEALED_ACH = 0.05;

    public SpacePhysics {
        noiseStd = noiseStd == null ? Map.of() : new TreeMap<>(noiseStd);
    }

    public double volumeM3() {
        return areaM2 * heightM;
    }

    public double window() {
        return windowM2 == null ? 0 : windowM2;
    }

    /**
     * @param temperature  ℃
     * @param humidity     상대 습도 %
     * @param co2          ppm
     * @param pm2_5        μg/㎥
     * @param illumination lux
     */
    public record InitialState(double temperature, double humidity, double co2, double pm2_5, double illumination) {
    }

    /**
     * @param heatW       1인당 현열(W)
     * @param co2Lph      1인당 CO2 배출(L/h)
     * @param moistureGph 1인당 수분(g/h)
     * @param noiseDb     1인당 소음 기여(dB, 활동 수준 1 기준)
     */
    public record PerPerson(double heatW, double co2Lph, double moistureGph, double noiseDb) {
    }

    /** 프리셋 기본값(SIM-01.02 "강의실·사무실·회의실") */
    public static SpacePhysics preset(SpacePreset preset) {
        return switch (preset) {
            case CLASSROOM -> new SpacePhysics(SpacePreset.CLASSROOM, 66, 3, 1.0, 40, 8.0, "S", 0.1,
                    new InitialState(26, 55, 450, 12, 300), true, 420, new PerPerson(100, 18, 50, 8), 30,
                    defaultNoise());
            case OFFICE -> new SpacePhysics(SpacePreset.OFFICE, 100, 2.8, 0.8, 50, 12.0, "S", 0.1,
                    new InitialState(24, 50, 450, 10, 400), true, 420, new PerPerson(100, 18, 50, 6), 32,
                    defaultNoise());
            case MEETING -> new SpacePhysics(SpacePreset.MEETING, 30, 2.7, 0.8, 20, 4.0, "E", 0.1,
                    new InitialState(24, 50, 450, 10, 350), true, 420, new PerPerson(100, 18, 50, 8), 30,
                    defaultNoise());
            case CUSTOM -> new SpacePhysics(SpacePreset.CUSTOM, 50, 3, 1.0, 30, 6.0, "S", 0.1,
                    new InitialState(24, 50, 450, 10, 300), true, 420, new PerPerson(100, 18, 50, 8), 30,
                    defaultNoise());
        };
    }

    private static Map<String, Double> defaultNoise() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("temperature", 0.1);
        m.put("humidity", 0.5);
        m.put("co2", 10.0);
        m.put("pm2_5", 1.0);
        m.put("illumination", 5.0);
        m.put("LAeq", 0.5);
        return m;
    }

    /** 범위 검사(erd CHECK와 같은 범위). 위반 항목 목록(field, 허용 범위). 비어 있으면 통과 */
    public List<String[]> violations() {
        List<String[]> v = new ArrayList<>();
        range(v, "physics.areaM2", areaM2, 1, 5000);
        range(v, "physics.heightM", heightM, 2, 20);
        range(v, "physics.uValue", uValue, 0.1, 6.0);
        range(v, "physics.envelopeM2", envelopeM2, 0, 20000);
        range(v, "physics.windowM2", window(), 0, 5000);
        range(v, "physics.solarGainFactor", solarGainFactor, 0, 1);
        range(v, "physics.outdoorCo2Ppm", outdoorCo2Ppm, 300, 2000);
        range(v, "physics.backgroundNoiseDb", backgroundNoiseDb, 0, 120);
        if (preset == null) {
            v.add(new String[]{"physics.preset", "CLASSROOM|OFFICE|MEETING|CUSTOM"});
        }
        if (initialState == null) {
            v.add(new String[]{"physics.initialState", "required"});
        } else {
            range(v, "physics.initialState.temperature", initialState.temperature(), -30, 60);
            range(v, "physics.initialState.humidity", initialState.humidity(), 0, 100);
            range(v, "physics.initialState.co2", initialState.co2(), 300, 10000);
            range(v, "physics.initialState.pm2_5", initialState.pm2_5(), 0, 1000);
            range(v, "physics.initialState.illumination", initialState.illumination(), 0, 100000);
        }
        if (perPerson == null) {
            v.add(new String[]{"physics.perPerson", "required"});
        } else {
            range(v, "physics.perPerson.heatW", perPerson.heatW(), 0, 500);
            range(v, "physics.perPerson.co2Lph", perPerson.co2Lph(), 0, 100);
            range(v, "physics.perPerson.moistureGph", perPerson.moistureGph(), 0, 500);
            range(v, "physics.perPerson.noiseDb", perPerson.noiseDb(), 0, 60);
        }
        noiseStd.forEach((k, s) -> range(v, "physics.noiseStd." + k, s == null ? -1 : s, 0, 1000));
        return v;
    }

    private static void range(List<String[]> v, String field, double value, double min, double max) {
        if (Double.isNaN(value) || value < min || value > max) {
            v.add(new String[]{field, min + "~" + max});
        }
    }
}
