package net.java21.data2flow.sim.physics.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 시나리오 외기 설정(SIM-04.01, Scenario {@code outdoor}). M3는 {@code DIURNAL}(일주기)만 실행한다. {@code WEATHER}(SIM-10.01)·{@code CSV}는
 * M7이며 실행을 시작하면 {@code SIM_WEATHER_DATA_MISSING}으로 거부한다.
 *
 * @param mode    DIURNAL, WEATHER, CSV
 * @param diurnal 일주기 설정. 없으면 기본값(최고 30℃·최저 22℃·15시·습도 60%)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutdoorSpec(String mode, Diurnal diurnal, Object weather, String csvFileId) {

    public static final String DIURNAL = "DIURNAL";

    public OutdoorSpec {
        mode = mode == null ? DIURNAL : mode;
        diurnal = diurnal == null && DIURNAL.equals(mode) ? Diurnal.DEFAULT : diurnal;
    }

    public static OutdoorSpec diurnal(double max, double min, double peakHour, double humidity) {
        return new OutdoorSpec(DIURNAL, new Diurnal(max, min, peakHour, humidity, 0.0, 15.0), null, null);
    }

    /** 일정한 외기(시험용) */
    public static OutdoorSpec constant(double temperature, double humidity) {
        return diurnal(temperature, temperature, 15, humidity);
    }

    /**
     * 일주기 외기. 온도 = 평균 + 진폭·cos(2π(h − 피크)/24), 습도는 온도와 반대로 움직인다(진폭 humidityAmplitude).
     *
     * @param max               최고 온도 ℃
     * @param min               최저 온도 ℃
     * @param peakHour          최고 온도 시각(조직 시간대, 0~24)
     * @param humidity          평균 상대 습도 %
     * @param humidityAmplitude 습도 진폭 %p. 없으면 0
     * @param pm2_5             외기 PM2.5. 없으면 15
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Diurnal(double max, double min, double peakHour, double humidity, Double humidityAmplitude, Double pm2_5) {
        public static final Diurnal DEFAULT = new Diurnal(30, 22, 15, 60, 0.0, 15.0);
    }

    public boolean runnable() {
        return DIURNAL.equals(mode) && diurnal != null;
    }

    public Outdoor at(Instant at, ZoneId zone, SolarModel sun, double co2) {
        Diurnal d = diurnal == null ? Diurnal.DEFAULT : diurnal;
        ZonedDateTime local = at.atZone(zone);
        double hour = local.getHour() + local.getMinute() / 60.0 + local.getSecond() / 3600.0;
        double phase = Math.cos(2 * Math.PI * (hour - d.peakHour()) / 24.0);
        double mean = (d.max() + d.min()) / 2.0;
        double amp = (d.max() - d.min()) / 2.0;
        double temp = mean + amp * phase;
        double rhAmp = d.humidityAmplitude() == null ? 0 : d.humidityAmplitude();
        double rh = Psychrometrics.clamp(d.humidity() - rhAmp * phase, 0, 100);
        double elevation = sun.elevation(at);
        return new Outdoor(temp, rh, co2, d.pm2_5() == null ? 15 : d.pm2_5(), sun.irradiance(elevation),
                sun.daylightLux(elevation), elevation);
    }
}
