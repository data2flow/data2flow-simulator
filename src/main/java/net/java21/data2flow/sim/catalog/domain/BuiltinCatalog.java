package net.java21.data2flow.sim.catalog.domain;

import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.sensor.domain.GeneratorSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 플랫폼 기본 가상 기기 카탈로그(SIM-09.01 표, SIM-02.01 아카데미 실측 6종, SIM-03.01 장비 11종)와 키트(SIM-09.07).
 * 모든 유형은 설정 없이 기본값만으로 동작한다. 시작할 때 {@code data2flow_sim.device_types}·{@code kits}(조직 0)에 맞춘다.
 *
 * <p>아카데미 6종은 DEV-03 모델 정의(core-api BuiltinCatalog)와 측정 항목·단위·보고 주기(600초)가 같다(TC-SIM-014).
 */
public final class BuiltinCatalog {

    public static final String TH_SENSOR = "th-sensor";
    public static final String CO2_SENSOR = "co2-sensor";
    public static final String PM_SENSOR = "pm-sensor";
    public static final String AQ_SENSOR = "aq-sensor";
    public static final String PIR_SENSOR = "pir-sensor";
    public static final String DOOR_SENSOR = "door-sensor";
    public static final String LUX_SENSOR = "lux-sensor";
    public static final String NOISE_SENSOR = "noise-sensor";
    public static final String POWER_METER = "power-meter";

    public static final String AIRCON = "aircon";
    public static final String AIR_PURIFIER = "air-purifier";
    public static final String HUMIDIFIER = "humidifier";
    public static final String DEHUMIDIFIER = "dehumidifier";
    public static final String VENTILATOR = "ventilator";
    public static final String HEATER = "heater";
    public static final String FAN = "fan";
    public static final String LIGHT = "light";
    public static final String SMART_PLUG = "smart-plug";
    public static final String WINDOW = "window";
    public static final String DOOR_LOCK = "door-lock";

    /** 아카데미 실측 모델 6종(DEV-03.02)과 연결한 유형 키 */
    public static final List<String> ACADEMY_MODELS = List.of("EM300-TH", "EM320-TH", "EM500-CO2", "AM103", "AM107", "WS302");

    /** 장비 11종(SIM-03.01) */
    public static final List<String> ACTUATOR_KEYS = List.of(AIRCON, AIR_PURIFIER, HUMIDIFIER, DEHUMIDIFIER, HEATER,
            VENTILATOR, FAN, LIGHT, SMART_PLUG, WINDOW, DOOR_LOCK);

    public static final String KIT_CLASSROOM = "classroom-standard";
    public static final String KIT_OFFICE = "office-standard";

    /** 플로우 템플릿 키(FLW-01.05와 합의할 이름) */
    public static final String FLOW_HOT_THEN_COOL = "hot-then-cool";
    public static final String FLOW_CO2_THEN_VENTILATE = "co2-then-ventilate";

    private static final List<DeviceTypeDef> TYPES = build();
    private static final List<KitDef> KITS = List.of(
            new KitDef(KIT_CLASSROOM, "표준 강의실 키트", List.of(
                    new KitDef.Item(TH_SENSOR, 2, "온습도", "MEASURES"),
                    new KitDef.Item(CO2_SENSOR, 1, "CO2", "MEASURES"),
                    new KitDef.Item(PIR_SENSOR, 1, "재실", "MEASURES"),
                    new KitDef.Item(AIRCON, 1, "에어컨", "CONTROLS"),
                    new KitDef.Item(AIR_PURIFIER, 1, "공기청정기", "CONTROLS"),
                    new KitDef.Item(VENTILATOR, 1, "환기", "CONTROLS")),
                    List.of(FLOW_HOT_THEN_COOL, FLOW_CO2_THEN_VENTILATE)),
            new KitDef(KIT_OFFICE, "사무실 키트", List.of(
                    new KitDef.Item(TH_SENSOR, 2, "온습도", "MEASURES"),
                    new KitDef.Item(AQ_SENSOR, 1, "공기질", "MEASURES"),
                    new KitDef.Item(PIR_SENSOR, 1, "재실", "MEASURES"),
                    new KitDef.Item(AIRCON, 1, "에어컨", "CONTROLS"),
                    new KitDef.Item(LIGHT, 2, "조명", "CONTROLS"),
                    new KitDef.Item(VENTILATOR, 1, "환기", "CONTROLS")),
                    List.of(FLOW_HOT_THEN_COOL)));

    private BuiltinCatalog() {
    }

    public static List<DeviceTypeDef> types() {
        return TYPES;
    }

    public static List<KitDef> kits() {
        return KITS;
    }

    public static Optional<DeviceTypeDef> type(String key) {
        return TYPES.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    public static Optional<KitDef> kit(String key) {
        return KITS.stream().filter(k -> k.key().equals(key)).findFirst();
    }

    /** 아카데미 모델 코드 → 유형 키(소문자) */
    public static String academyTypeKey(String modelCode) {
        return modelCode.toLowerCase();
    }

    // ───────────── 측정 항목 정의(DEV-03 단위·범위, 분해능) ─────────────
    static MetricDef temperature() { return MetricDef.physics("temperature", "℃", 0.1, -40.0, 85.0); }
    static MetricDef humidity() { return MetricDef.physics("humidity", "%", 0.5, 0.0, 100.0); }
    static MetricDef co2() { return MetricDef.physics("co2", "ppm", 1, 400.0, 5000.0); }
    static MetricDef battery() { return MetricDef.derived("battery", "%", 1, 0.0, 100.0); }
    static MetricDef pressure() {
        return MetricDef.generated("pressure", "hPa", GeneratorSpec.randomWalk(1008, 995, 1025, 0.3), 0.1, 300.0, 1100.0);
    }
    static MetricDef tvoc() {
        return MetricDef.generated("tvoc", null, GeneratorSpec.randomWalk(150, 30, 600, 15), 1, 0.0, 60000.0);
    }
    static MetricDef illumination() { return MetricDef.physics("illumination", "lux", 1, 0.0, 60000.0); }
    static MetricDef infrared() {
        return MetricDef.generated("infrared", null, GeneratorSpec.randomWalk(20, 0, 200, 5), 1, 0.0, null);
    }
    static MetricDef activity() { return MetricDef.derived("activity", null, 1, 0.0, null); }
    static MetricDef occupancy() { return MetricDef.physics("occupancy", null, 1, 0.0, null); }
    static MetricDef laeq() { return MetricDef.physics("LAeq", "dB", 0.1, 30.0, 130.0); }
    static MetricDef lai() { return MetricDef.physics("LAI", "dB", 0.1, 30.0, 130.0); }
    static MetricDef laimax() { return MetricDef.physics("LAImax", "dB", 0.1, 30.0, 130.0); }
    static MetricDef pm(String key) { return MetricDef.physics(key, "μg/㎥", 1, 0.0, 1000.0); }
    static MetricDef door() { return MetricDef.physics("door", null, 1, 0.0, 1.0); }
    static MetricDef power() { return MetricDef.derived("power", "W", 0.1, 0.0, null); }
    static MetricDef energy() { return MetricDef.derived("energy", "kWh", 0.001, 0.0, null); }

    // ───────────── 공통 센서 특성 ─────────────
    static PropertyDef accuracy(String metric, String unit, double max, double def) {
        return PropertyDef.number(metric + "Accuracy", metric + " 측정 오차(±, 2σ)", unit, 0, max, def,
                "측정 잡음. ±값을 2σ(95%)로 본다. 0이면 물리 모델 값 그대로(SIM-09.05)");
    }

    static PropertyDef responseDelay(double def) {
        return PropertyDef.number("responseDelaySec", "응답 지연", "초", 0, 600, def, "물리 값이 출력에 나타나기까지 걸리는 시간");
    }

    static PropertyDef batteryLife(double years) {
        return PropertyDef.number("batteryLifeYears", "배터리 수명", "년", 0, 20, years, "보고 주기로 보고당 소모량을 정한다. 0이면 소모 없음");
    }

    static PropertyDef drift(String metric, String unit, double def) {
        return PropertyDef.number(metric + "DriftPerYear", metric + " 드리프트", unit + "/년", -1000, 1000, def, "장기 추세(SIM-09.05)");
    }

    static PropertyDef reactionDelay(double def) {
        return PropertyDef.number("reactionDelaySec", "반응 지연", "초", 0, 1800, def, "명령이 물리 모델에 반영되기까지(시뮬레이션 시간)");
    }

    static PropertyDef ratedPowerW(double def, double max) {
        return PropertyDef.number("ratedPowerW", "소비 전력", "W", 0, max, def, "켜져 있을 때 소비 전력(SIM-03.05)");
    }

    static PropertyDef standbyW(double def) {
        return PropertyDef.number("standbyW", "대기 전력", "W", 0, 50, def, "꺼져 있을 때 소비 전력");
    }

    static PropertyDef noiseDb(double def) {
        return PropertyDef.number("noiseDb", "운전 소음", "dB", 0, 90, def, "켜져 있을 때 공간 소음 기여");
    }

    private static DeviceTypeDef sensor(String key, String name, List<MetricDef> metrics, List<PropertyDef> props, String model,
                                        int intervalSec, boolean onChange) {
        return new DeviceTypeDef(key, name, DeviceTypeDef.SENSOR, metrics, List.of(), props, List.of(), model,
                PayloadFormat.CHIRPSTACK_V4, intervalSec, onChange);
    }

    private static DeviceTypeDef actuator(String key, String name, List<String> capabilities, List<PropertyDef> props,
                                          List<PhysicsEffect> effects) {
        return new DeviceTypeDef(key, name, DeviceTypeDef.ACTUATOR, List.of(power(), energy()), capabilities, props, effects,
                null, PayloadFormat.CHIRPSTACK_V4, 300, false);
    }

    private static List<DeviceTypeDef> build() {
        List<DeviceTypeDef> list = new ArrayList<>();
        PropertyDef tAcc = accuracy("temperature", "℃", 5, 0.3);
        PropertyDef hAcc = accuracy("humidity", "%", 20, 3);
        PropertyDef cAcc = accuracy("co2", "ppm", 500, 50);

        // ── 카탈로그 센서(SIM-09.01 표)
        list.add(sensor(TH_SENSOR, "온습도 센서", List.of(temperature(), humidity(), battery()),
                List.of(tAcc, hAcc, responseDelay(60), batteryLife(2)), "EM320-TH", 60, false));
        list.add(sensor(CO2_SENSOR, "CO2 센서(NDIR)", List.of(co2(), temperature(), humidity()),
                List.of(cAcc, tAcc, hAcc, drift("co2", "ppm", 20), responseDelay(60), batteryLife(0)), "AM103", 60, false));
        list.add(sensor(PM_SENSOR, "미세먼지 센서", List.of(pm("pm1_0"), pm("pm2_5"), pm("pm10")),
                List.of(accuracy("pm1_0", "μg/㎥", 50, 3), accuracy("pm2_5", "μg/㎥", 50, 5), accuracy("pm10", "μg/㎥", 80, 8),
                        PropertyDef.number("humidityFactor", "습도 영향 계수", null, 0, 2, 0.3, "상대 습도 70% 넘으면 값이 이만큼 커진다"),
                        responseDelay(30)), null, 60, false));
        list.add(sensor(AQ_SENSOR, "복합 공기질 센서(AM107형)",
                List.of(temperature(), humidity(), co2(), tvoc(), pressure(), illumination(), activity()),
                List.of(tAcc, hAcc, cAcc, accuracy("illumination", "lux", 500, 10), responseDelay(60), batteryLife(0)),
                null, 60, false));
        list.add(sensor(PIR_SENSOR, "재실(PIR) 센서", List.of(occupancy(), activity()),
                List.of(PropertyDef.number("detectionProbability", "감지 확률", "%", 0, 100, 95, "재실이 있을 때 감지할 확률"),
                        PropertyDef.number("falsePositivePct", "오탐", "%", 0, 100, 1, "재실이 없을 때 잘못 감지할 확률"),
                        PropertyDef.number("holdTimeSec", "유지 시간", "초", 0, 3600, 300, "감지 뒤 재실로 유지하는 시간")),
                null, 60, false));
        list.add(sensor(DOOR_SENSOR, "문·창문 열림 센서", List.of(door()),
                List.of(PropertyDef.number("heartbeatSec", "하트비트", "초", 60, 86400, 3600, "변화가 없을 때 보고 주기")),
                null, 3600, true));
        list.add(sensor(LUX_SENSOR, "조도 센서", List.of(illumination()),
                List.of(accuracy("illumination", "lux", 500, 10), PropertyDef.bool("windowSide", "창가 설치", false, "일사 영향 2배")),
                null, 60, false));
        list.add(sensor(NOISE_SENSOR, "소음 센서", List.of(laeq(), laimax()),
                List.of(accuracy("LAeq", "dB", 10, 1)), null, 60, false));
        list.add(sensor(POWER_METER, "전력량계", List.of(power(), energy()), List.of(), null, 60, false));

        // ── 아카데미 실측 6종(DEV-03.02, 보고 주기 600초 = Milesight 공장 설정)
        list.add(sensor("em300-th", "EM300-TH 온습도 센서", List.of(temperature(), humidity()),
                List.of(tAcc, hAcc, responseDelay(60), batteryLife(0)), "EM300-TH", 600, false));
        list.add(sensor("em320-th", "EM320-TH 온습도 센서", List.of(temperature(), humidity(), battery()),
                List.of(tAcc, hAcc, responseDelay(60), batteryLife(5)), "EM320-TH", 600, false));
        list.add(sensor("em500-co2", "EM500-CO2 센서", List.of(co2(), pressure(), temperature(), humidity()),
                List.of(cAcc, tAcc, hAcc, drift("co2", "ppm", 20), responseDelay(60), batteryLife(0)), "EM500-CO2", 600, false));
        list.add(sensor("am103", "AM103 실내 공기질 센서", List.of(co2(), temperature(), humidity(), battery()),
                List.of(cAcc, tAcc, hAcc, responseDelay(60), batteryLife(3)), "AM103", 600, false));
        list.add(sensor("am107", "AM107 실내 공기질 센서",
                List.of(co2(), tvoc(), pressure(), illumination(), infrared(), activity(), temperature(), humidity()),
                List.of(cAcc, tAcc, hAcc, accuracy("illumination", "lux", 500, 10), responseDelay(60), batteryLife(0)),
                "AM107", 600, false));
        list.add(sensor("ws302", "WS302 소음 센서", List.of(laeq(), lai(), laimax(), battery()),
                List.of(accuracy("LAeq", "dB", 10, 1), batteryLife(3)), "WS302", 600, false));

        // ── 장비 11종(SIM-03.01, SIM-09 표)
        list.add(actuator(AIRCON, "에어컨", List.of("Switch", "Thermostat", "FanSpeed"), List.of(
                PropertyDef.number("coolingCapacityKw", "냉방 능력", "kW", 0.5, 20, 3.5, "온도 하강 속도(SIM-09.04)"),
                PropertyDef.number("heatingCapacityKw", "난방 능력", "kW", 0.5, 20, 4.0, "난방 출력"),
                PropertyDef.number("setpointMin", "설정 하한", "℃", 10, 30, 18, "설정 온도 범위"),
                PropertyDef.number("setpointMax", "설정 상한", "℃", 18, 35, 30, "설정 온도 범위"),
                ratedPowerW(1200, 10000), standbyW(2), reactionDelay(120), noiseDb(40)),
                List.of(new PhysicsEffect("COOLING", "coolingCapacityKw"), new PhysicsEffect("HEATING", "heatingCapacityKw"),
                        new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(AIR_PURIFIER, "공기청정기", List.of("Switch", "FanSpeed"), List.of(
                PropertyDef.number("cadrM3h", "청정 능력(CADR)", "㎥/h", 30, 2000, 300, "PM2.5 감소 속도(SIM-09.04)"),
                PropertyDef.number("filterLifeHours", "필터 수명", "시간", 100, 20000, 4320, "필터 잔여 수명 계산"),
                ratedPowerW(40, 500), standbyW(1), reactionDelay(5), noiseDb(38)),
                List.of(new PhysicsEffect("PURIFY", "cadrM3h"), new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(HUMIDIFIER, "가습기", List.of("Switch", "custom.Humidifier"), List.of(
                PropertyDef.number("outputMlph", "가습량", "ml/h", 50, 3000, 300, "습도 상승 속도"),
                PropertyDef.number("tankMl", "물통 용량", "ml", 500, 20000, 4000, "물통"),
                ratedPowerW(30, 1000), standbyW(1), reactionDelay(30), noiseDb(30)),
                List.of(new PhysicsEffect("HUMIDIFY", "outputMlph"), new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(DEHUMIDIFIER, "제습기", List.of("Switch", "custom.Dehumidifier"), List.of(
                PropertyDef.number("capacityLpd", "제습량", "L/일", 1, 100, 10, "습도 하강 속도"),
                PropertyDef.number("tankMl", "물통 용량", "ml", 500, 20000, 3000, "물통"),
                ratedPowerW(250, 2000), standbyW(1), reactionDelay(60), noiseDb(42)),
                List.of(new PhysicsEffect("DEHUMIDIFY", "capacityLpd"), new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(HEATER, "히터", List.of("Switch", "Dimmer"), List.of(
                PropertyDef.number("outputKw", "출력", "kW", 0.3, 10, 2.0, "최대 발열(밝기 대신 출력 %)"),
                standbyW(1), reactionDelay(30), noiseDb(0)),
                List.of(new PhysicsEffect("HEATING", "outputKw"), new PhysicsEffect("POWER", "outputKw"))));
        list.add(actuator(VENTILATOR, "환기 장치(ERV)", List.of("Switch", "Ventilation"), List.of(
                PropertyDef.number("airflowM3h", "단계당 환기량", "㎥/h", 50, 2000, 250, "Ventilation level 1~3에 곱한다"),
                PropertyDef.number("heatRecovery", "열회수 효율", null, 0, 0.95, 0.7, "환기로 잃는 열의 회수 비율"),
                ratedPowerW(120, 2000), standbyW(1), reactionDelay(10), noiseDb(35)),
                List.of(new PhysicsEffect("VENTILATION", "airflowM3h"), new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(FAN, "선풍기", List.of("Switch", "FanSpeed"), List.of(
                ratedPowerW(50, 500), standbyW(0.5), reactionDelay(2), noiseDb(40)),
                List.of(new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(LIGHT, "조명", List.of("Switch", "Dimmer"), List.of(
                PropertyDef.number("maxLux", "최대 조도 기여", "lux", 50, 3000, 500, "Dimmer 100%일 때 조도"),
                ratedPowerW(200, 5000), standbyW(0.5), reactionDelay(1)),
                List.of(new PhysicsEffect("LIGHT", "maxLux"), new PhysicsEffect("POWER", "ratedPowerW"))));
        list.add(actuator(SMART_PLUG, "스마트 플러그", List.of("Switch"), List.of(
                PropertyDef.number("loadW", "연결 부하", "W", 0, 3500, 100, "켜져 있을 때 부하(실내 발열로 반영)"),
                standbyW(0.5), reactionDelay(1)),
                List.of(new PhysicsEffect("POWER", "loadW"))));
        list.add(actuator(WINDOW, "창문", List.of("Switch"), List.of(reactionDelay(5)), List.of()));
        list.add(actuator(DOOR_LOCK, "도어락", List.of("Lock"), List.of(
                batteryLife(1), reactionDelay(2), standbyW(0.1)), List.of()));
        return List.copyOf(list);
    }
}
