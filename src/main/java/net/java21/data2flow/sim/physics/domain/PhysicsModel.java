package net.java21.data2flow.sim.physics.domain;

/**
 * 공간 물리 모델(SIM-01.03~06, SIM domain-model "물리 모델"). 한 틱(기본 시뮬레이션 10초) 동안 외기·재실·장비 영향을 일정하다고 보고
 * 선형 미분방정식을 <b>정확한 지수 해</b>로 적분한다. 그래서 틱 길이(1초·10초)와 상관없이 결과가 거의 같고(TC-SIM-008), 극단값에서도
 * 발산하지 않는다.
 *
 * <ul>
 *   <li>온도: {@code C·dT/dt = (U·A + 침기 + 환기·(1−열회수))·(T_out − T) + n·q + 일사 + 고정 발열 + Q_hvac},
 *       C = 공기 열용량 × 부피 × 가구 보정 3. 냉난방은 설정 온도 0.5℃ 차이에서 최대 출력에 닿는 비례 제어(인버터)</li>
 *   <li>CO2: {@code V·dC/dt = n·G − Q·(C − C_out)}, Q = 침기 + 환기(+ 창문 열림)</li>
 *   <li>습도: 절대 습도 수지(재실 수분, 가습·제습, 냉방 코일 응축, 외기 교환) → 상대 습도는 온도로 환산(0~100% 고정)</li>
 *   <li>PM2.5: {@code V·dP/dt = 유입 − (Q + CADR + 침착)·P}</li>
 *   <li>조도: 주광 × 창 계수 + 조명. 소음: 배경 + 10·log10(1 + n·활동·10^(1인 기여/10)) ⊕ 장비 소음</li>
 * </ul>
 */
public final class PhysicsModel {

    /** 공기 밀도 × 비열 J/(㎥·K) */
    public static final double RHO_CP = 1.2 * 1005.0;
    /** 가구·벽체 열용량 보정 */
    public static final double FURNITURE_FACTOR = 3.0;
    /** 냉난방이 최대 출력에 닿는 설정 온도 차(℃) */
    public static final double PROPORTIONAL_BAND = 0.5;
    /** 냉방 코일 온도 ℃(응축 제습 기준) */
    public static final double COIL_TEMPERATURE = 12.0;
    /** 냉방 능력 1kW당 코일 풍량 ㎥/h */
    public static final double COIL_AIRFLOW_PER_KW = 170.0;
    /** 압축기가 돌 때 최소 코일 통과 비율 */
    public static final double MIN_COIL_DUTY = 0.3;
    /** 창문을 열면 더해지는 환기 회/h, 문은 */
    public static final double WINDOW_OPEN_ACH = 3.0;
    public static final double DOOR_OPEN_ACH = 0.5;
    /** 실내 PM 침착률 /h */
    public static final double PM_DEPOSITION_PER_HOUR = 0.2;
    /** 환기 필터의 PM 제거율 */
    public static final double VENT_FILTER_EFFICIENCY = 0.7;
    /** 창 면적 비 1일 때 주광률 */
    public static final double DAYLIGHT_FACTOR = 0.1;

    private PhysicsModel() {
    }

    /**
     * 이번 틱의 냉난방 결과(전력 계산용).
     *
     * @param hvacW        평균 냉난방 열량(W, 냉방은 음수)
     * @param hvacLoadRatio 최대 출력 대비 부하율 0~1
     * @param mode          실제로 돈 모드(AUTO는 COOL·HEAT·FAN 중 하나로 풀림)
     */
    public record StepResult(double hvacW, double hvacLoadRatio, ActuatorEffects.HvacMode mode) {
        public static final StepResult IDLE = new StepResult(0, 0, ActuatorEffects.HvacMode.OFF);
    }

    public static StepResult step(SpaceState s, SpacePhysics p, Outdoor o, ActuatorEffects e, double dtSec) {
        double volume = p.volumeM3();
        boolean windowOpen = s.windowOpen || e.windowOpen;
        double ach = (p.outdoorLinked() ? SpacePhysics.INFILTRATION_ACH : SpacePhysics.SEALED_ACH)
                + (windowOpen ? WINDOW_OPEN_ACH : 0) + (s.doorOpen ? DOOR_OPEN_ACH : 0);
        double infiltrationM3h = ach * volume;
        double ventilationM3h = Math.max(0, e.ventilationM3h);

        // ── 온도
        double capacity = RHO_CP * volume * FURNITURE_FACTOR;
        double gEnv = p.uValue() * p.envelopeM2();
        double gAir = RHO_CP * infiltrationM3h / 3600.0 + RHO_CP * ventilationM3h / 3600.0 * (1 - clamp01(e.heatRecovery));
        double g = gEnv + gAir;
        double internal = s.occupancy * p.perPerson().heatW() + solarGain(p, o) + Math.max(0, e.fixedHeatW);

        ActuatorEffects.HvacMode mode = resolveMode(e, s.temperature);
        double cap = switch (mode) {
            case COOL -> e.coolingCapacityW;
            case DRY -> e.coolingCapacityW * 0.4;
            case HEAT -> e.heatingCapacityW;
            default -> 0;
        };
        double a = g * o.temperature() + internal;
        double b = g;
        boolean linear = false;
        double constantHvac = 0;
        double k = cap / PROPORTIONAL_BAND;
        if (cap > 0) {
            double demand = mode == ActuatorEffects.HvacMode.HEAT ? k * (e.setpoint - s.temperature) : k * (s.temperature - e.setpoint);
            if (demand >= cap) {
                constantHvac = mode == ActuatorEffects.HvacMode.HEAT ? cap : -cap;
                a += constantHvac;
            } else if (demand > 0) {
                linear = true;
                a += k * e.setpoint;
                b += k;
            }
        }
        double t0 = s.temperature;
        s.temperature = integrate(t0, a, b, capacity, dtSec);
        double hvacW;
        if (linear) {
            double mid = (t0 + s.temperature) / 2.0;
            hvacW = mode == ActuatorEffects.HvacMode.HEAT ? k * Math.max(0, e.setpoint - mid) : -k * Math.max(0, mid - e.setpoint);
        } else {
            hvacW = constantHvac;
        }
        double loadRatio = cap > 0 ? Math.min(1, Math.abs(hvacW) / cap) : 0;

        // ── 습도(절대 습도 g/㎥)
        double rh = Psychrometrics.relativeHumidity(s.temperature, s.absHumidity);
        double sourceGph = s.occupancy * p.perPerson().moistureGph();
        if (e.humidifyGph > 0 && rh < e.humidifyTarget) {
            sourceGph += e.humidifyGph;
        }
        if (e.dehumidifyGph > 0 && rh > e.dehumidifyTarget) {
            sourceGph -= e.dehumidifyGph;
        }
        double outdoorAbs = Psychrometrics.absoluteHumidity(o.temperature(), o.humidity());
        double exchangeM3h = infiltrationM3h + ventilationM3h;
        double ha = sourceGph / 3600.0 + exchangeM3h / 3600.0 * outdoorAbs;
        double hb = exchangeM3h / 3600.0;
        // 냉방 응축: 증발기(코일) 통과 공기가 코일 온도의 포화 습도까지 마른다. 압축기가 돌면 최소 30% 통과로 본다
        double coilSat = Psychrometrics.saturationDensity(COIL_TEMPERATURE);
        if (hvacW < 0 && s.absHumidity > coilSat) {
            double coilM3s = cap / 1000.0 * COIL_AIRFLOW_PER_KW / 3600.0 * Math.max(loadRatio, MIN_COIL_DUTY);
            ha += coilM3s * coilSat;
            hb += coilM3s;
        }
        s.absHumidity = integrate(s.absHumidity, ha, hb, volume, dtSec);
        s.absHumidity = Math.max(0, Math.min(s.absHumidity, Psychrometrics.saturationDensity(s.temperature)));

        // ── CO2(ppm)
        double genM3s = s.occupancy * p.perPerson().co2Lph() / 1000.0 / 3600.0;
        s.co2 = integrate(s.co2, genM3s * 1e6 + exchangeM3h / 3600.0 * o.co2(), exchangeM3h / 3600.0, volume, dtSec);

        // ── PM2.5
        double inflow = (infiltrationM3h + ventilationM3h * (1 - VENT_FILTER_EFFICIENCY)) / 3600.0 * o.pm25();
        double sink = (exchangeM3h + Math.max(0, e.cadrM3h)) / 3600.0 + PM_DEPOSITION_PER_HOUR * volume / 3600.0;
        s.pm25 = Math.max(0, integrate(s.pm25, inflow, sink, volume, dtSec));

        // ── 조도·소음
        double windowRatio = p.areaM2() > 0 ? p.window() / p.areaM2() : 0;
        s.illumination = Math.max(0, o.daylightLux() * windowRatio * DAYLIGHT_FACTOR * orientation(p.windowOrientation())
                + Math.max(0, e.lightLux));
        double people = s.occupancy * Math.max(0, s.activityLevel) * Math.pow(10, p.perPerson().noiseDb() / 10.0);
        double level = p.backgroundNoiseDb() + 10 * Math.log10(1 + people);
        double energy = Math.pow(10, level / 10.0);
        for (double src : e.noiseSourcesDb) {
            energy += Math.pow(10, src / 10.0);
        }
        s.noise = 10 * Math.log10(energy);
        return new StepResult(hvacW, loadRatio, mode);
    }

    /** {@code V·dX/dt = a − b·X}의 dt 뒤 값(정확한 지수 해). b = 0이면 선형 증가 */
    static double integrate(double x, double a, double b, double capacity, double dtSec) {
        if (b <= 1e-12) {
            return x + a * dtSec / capacity;
        }
        double eq = a / b;
        return eq + (x - eq) * Math.exp(-b * dtSec / capacity);
    }

    static ActuatorEffects.HvacMode resolveMode(ActuatorEffects e, double temperature) {
        if (e.hvacMode != ActuatorEffects.HvacMode.AUTO) {
            return e.hvacMode;
        }
        if (temperature > e.setpoint + PROPORTIONAL_BAND) {
            return ActuatorEffects.HvacMode.COOL;
        }
        if (temperature < e.setpoint - PROPORTIONAL_BAND) {
            return ActuatorEffects.HvacMode.HEAT;
        }
        return ActuatorEffects.HvacMode.FAN;
    }

    static double solarGain(SpacePhysics p, Outdoor o) {
        return p.window() * o.irradiance() * p.solarGainFactor() * orientation(p.windowOrientation());
    }

    static double orientation(String o) {
        if (o == null) {
            return 1.0;
        }
        return switch (o.toUpperCase()) {
            case "N" -> 0.35;
            case "E", "W" -> 0.75;
            default -> 1.0;
        };
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
