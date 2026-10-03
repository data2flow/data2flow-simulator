package net.java21.data2flow.sim.physics.domain;

/** 습공기 계산(Magnus 식). 절대 습도 g/㎥ ↔ 상대 습도 % */
public final class Psychrometrics {

    private Psychrometrics() {
    }

    /** 포화 수증기압 hPa */
    public static double saturationPressureHpa(double temperatureC) {
        return 6.112 * Math.exp(17.62 * temperatureC / (243.12 + temperatureC));
    }

    /** 포화 절대 습도 g/㎥ */
    public static double saturationDensity(double temperatureC) {
        return saturationPressureHpa(temperatureC) * 100.0 / (461.5 * (temperatureC + 273.15)) * 1000.0;
    }

    public static double absoluteHumidity(double temperatureC, double relativeHumidityPct) {
        return saturationDensity(temperatureC) * clamp(relativeHumidityPct, 0, 100) / 100.0;
    }

    public static double relativeHumidity(double temperatureC, double absoluteHumidity) {
        return clamp(absoluteHumidity / saturationDensity(temperatureC) * 100.0, 0, 100);
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
