package net.java21.data2flow.sim.physics.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 태양 고도(SIM-01.06 "일출·일몰"). NOAA 간이식: 적위 + 균시차 + 경도 보정. 기본 위치는 서울(위도 37.5°, 경도 127.0°).
 * 일사량과 주광 조도는 맑은 날 기준 고도 사인 비례(최대 900W/㎡, 100,000lux).
 */
public final class SolarModel {

    public static final double SEOUL_LATITUDE = 37.5;
    public static final double SEOUL_LONGITUDE = 127.0;
    public static final double MAX_IRRADIANCE = 900.0;
    public static final double MAX_DAYLIGHT_LUX = 100_000.0;

    private final double latitude;
    private final double longitude;

    public SolarModel(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static SolarModel seoul() {
        return new SolarModel(SEOUL_LATITUDE, SEOUL_LONGITUDE);
    }

    /** 태양 고도(도). 음수면 해가 진 상태 */
    public double elevation(Instant at) {
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        int dayOfYear = utc.getDayOfYear();
        double gamma = 2 * Math.PI / 365.0 * (dayOfYear - 1 + (utc.getHour() - 12) / 24.0);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma) - 0.006758 * Math.cos(2 * gamma)
                + 0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double minutes = utc.getHour() * 60 + utc.getMinute() + utc.getSecond() / 60.0;
        double trueSolarMinutes = minutes + eqTime + 4 * longitude;
        double hourAngle = Math.toRadians(trueSolarMinutes / 4.0 - 180.0);
        double lat = Math.toRadians(latitude);
        double cosZenith = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(hourAngle);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, cosZenith))));
    }

    public double irradiance(double elevationDeg) {
        return elevationDeg <= 0 ? 0 : MAX_IRRADIANCE * Math.sin(Math.toRadians(elevationDeg));
    }

    public double daylightLux(double elevationDeg) {
        return elevationDeg <= 0 ? 0 : MAX_DAYLIGHT_LUX * Math.sin(Math.toRadians(elevationDeg));
    }
}
