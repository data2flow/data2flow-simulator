package net.java21.data2flow.sim.physics.domain;

/**
 * 어느 순간의 외기 조건.
 *
 * @param temperature  외기 온도 ℃
 * @param humidity     외기 상대 습도 %
 * @param co2          외기 CO2 ppm
 * @param pm25         외기 PM2.5 μg/㎥
 * @param irradiance   수평면 일사량 W/㎡(맑은 날 기준)
 * @param daylightLux  수평면 주광 조도 lux
 * @param sunElevation 태양 고도(도)
 */
public record Outdoor(double temperature, double humidity, double co2, double pm25, double irradiance, double daylightLux,
                      double sunElevation) {
}
