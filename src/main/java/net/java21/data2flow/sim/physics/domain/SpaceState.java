package net.java21.data2flow.sim.physics.domain;

/**
 * 가상 공간의 순간 상태(SIM-01.03~06). 체크포인트에 그대로 직렬화하므로 공개 필드만 둔다.
 * 습도는 절대 습도(g/㎥)로 계산하고 상대 습도는 온도에서 다시 구한다(냉방으로 온도가 내려가면 상대 습도가 오르는 효과까지 반영).
 */
public final class SpaceState {

    /** 실내 온도 ℃ */
    public double temperature;
    /** 절대 습도 g/㎥ */
    public double absHumidity;
    /** CO2 ppm */
    public double co2;
    /** PM2.5 μg/㎥ */
    public double pm25;
    /** 조도 lux */
    public double illumination;
    /** 소음 dB(A) */
    public double noise;
    /** 재실 인원 */
    public int occupancy;
    /** 활동 수준(1=조용함, 2=수업, 4=쉬는 시간) */
    public double activityLevel = 2.0;
    /** 창문 열림(시나리오 OPENING 또는 창문 장비) */
    public boolean windowOpen;
    /** 문 열림 */
    public boolean doorOpen;

    public SpaceState() {
    }

    public static SpaceState initial(SpacePhysics p) {
        SpaceState s = new SpaceState();
        SpacePhysics.InitialState i = p.initialState();
        s.temperature = i.temperature();
        s.absHumidity = Psychrometrics.absoluteHumidity(i.temperature(), i.humidity());
        s.co2 = i.co2();
        s.pm25 = i.pm2_5();
        s.illumination = i.illumination();
        s.noise = p.backgroundNoiseDb();
        return s;
    }

    /** 상대 습도 %(0~100) */
    public double relativeHumidity() {
        return Psychrometrics.relativeHumidity(temperature, absHumidity);
    }

    /** 측정 항목 키로 물리 값을 읽는다. 공간 물리와 관계없는 항목이면 NaN */
    public double metric(String key) {
        return switch (key) {
            case "temperature" -> temperature;
            case "humidity" -> relativeHumidity();
            case "co2" -> co2;
            case "pm2_5", "pm25" -> pm25;
            case "pm10" -> pm25 * 1.6;
            case "pm1_0" -> pm25 * 0.7;
            case "illumination" -> illumination;
            case "LAeq", "noise" -> noise;
            case "LAI" -> noise + 4;
            case "LAImax" -> noise + 9;
            case "occupancy" -> occupancy;
            case "door" -> doorOpen || windowOpen ? 1 : 0;
            case "window" -> windowOpen ? 1 : 0;
            default -> Double.NaN;
        };
    }

    public SpaceState copy() {
        SpaceState s = new SpaceState();
        s.temperature = temperature;
        s.absHumidity = absHumidity;
        s.co2 = co2;
        s.pm25 = pm25;
        s.illumination = illumination;
        s.noise = noise;
        s.occupancy = occupancy;
        s.activityLevel = activityLevel;
        s.windowOpen = windowOpen;
        s.doorOpen = doorOpen;
        return s;
    }
}
