package net.java21.data2flow.sim.actuator.domain;

import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.physics.domain.ActuatorEffects;
import net.java21.data2flow.sim.physics.domain.PhysicsModel;

import java.util.Map;

/**
 * 장비 상태·특성 → 물리 영향(SIM-03.03, SIM-09.04)과 소비 전력(SIM-03.05).
 * 물리 영향은 반응 지연이 지난 <b>유효 상태</b>로 계산한다(BR-SIM-05).
 */
public final class ActuatorPhysics {

    /** 에어컨 실내기 팬 전력(W). 압축기 전력은 (정격 − 팬) × 부하율 */
    public static final double AIRCON_FAN_W = 50;

    private ActuatorPhysics() {
    }

    /** 풍량 단계 → 냉난방 출력 비(1단 0.6, 2단 0.8, 3단·자동 1.0). high가 low보다 빨리 수렴(TC-SIM-033) */
    public static double fanFactor(ActuatorState s) {
        if (Boolean.TRUE.equals(s.get("FanSpeed", "auto"))) {
            return 1.0;
        }
        int level = (int) s.number("FanSpeed", "level", 3);
        return switch (level) {
            case 1 -> 0.6;
            case 2 -> 0.8;
            default -> 1.0;
        };
    }

    public static void addEffects(String typeKey, Map<String, Object> p, ActuatorState s, ActuatorEffects e) {
        if (!s.on() && !BuiltinCatalog.DOOR_LOCK.equals(typeKey)) {
            return;
        }
        switch (typeKey) {
            case BuiltinCatalog.AIRCON -> {
                String mode = s.text("Thermostat", "mode", "off");
                ActuatorEffects.HvacMode m = switch (mode) {
                    case "cool" -> ActuatorEffects.HvacMode.COOL;
                    case "heat" -> ActuatorEffects.HvacMode.HEAT;
                    case "dry" -> ActuatorEffects.HvacMode.DRY;
                    case "fan" -> ActuatorEffects.HvacMode.FAN;
                    case "auto" -> ActuatorEffects.HvacMode.AUTO;
                    default -> ActuatorEffects.HvacMode.OFF;
                };
                if (m == ActuatorEffects.HvacMode.OFF) {
                    return;
                }
                double f = fanFactor(s);
                e.hvacMode = m;
                e.coolingCapacityW += num(p, "coolingCapacityKw", 3.5) * 1000 * f;
                e.heatingCapacityW += num(p, "heatingCapacityKw", 4.0) * 1000 * f;
                e.setpoint = s.number("Thermostat", "targetTemperature", 24);
                e.noiseSourcesDb.add(num(p, "noiseDb", 40) + 10 * Math.log10(f));
            }
            case BuiltinCatalog.AIR_PURIFIER -> {
                e.cadrM3h += num(p, "cadrM3h", 300) * levelRatio(s);
                e.noiseSourcesDb.add(num(p, "noiseDb", 38));
            }
            case BuiltinCatalog.HUMIDIFIER -> {
                e.humidifyGph += num(p, "outputMlph", 300);
                e.humidifyTarget = s.number("custom.Humidifier", "targetHumidity", 50);
            }
            case BuiltinCatalog.DEHUMIDIFIER -> {
                e.dehumidifyGph += num(p, "capacityLpd", 10) * 1000.0 / 24.0;
                e.dehumidifyTarget = s.number("custom.Dehumidifier", "targetHumidity", 50);
                e.fixedHeatW += num(p, "ratedPowerW", 250);
                e.noiseSourcesDb.add(num(p, "noiseDb", 42));
            }
            case BuiltinCatalog.HEATER -> e.fixedHeatW += num(p, "outputKw", 2) * 1000 * s.number("Dimmer", "level", 100) / 100.0;
            case BuiltinCatalog.VENTILATOR -> {
                if (!"off".equals(s.text("Ventilation", "mode", "on"))) {
                    e.ventilationM3h += num(p, "airflowM3h", 250) * s.number("Ventilation", "level", 1);
                    e.heatRecovery = num(p, "heatRecovery", 0.7);
                    e.noiseSourcesDb.add(num(p, "noiseDb", 35));
                }
            }
            case BuiltinCatalog.FAN -> {
                e.fixedHeatW += num(p, "ratedPowerW", 50) * levelRatio(s);
                e.noiseSourcesDb.add(num(p, "noiseDb", 40));
            }
            case BuiltinCatalog.LIGHT -> {
                double level = s.number("Dimmer", "level", 100) / 100.0;
                e.lightLux += num(p, "maxLux", 500) * level;
                e.fixedHeatW += num(p, "ratedPowerW", 200) * level;
            }
            case BuiltinCatalog.SMART_PLUG -> e.fixedHeatW += num(p, "loadW", 100);
            case BuiltinCatalog.WINDOW -> e.windowOpen = true;
            default -> {
            }
        }
    }

    /**
     * 순간 소비 전력(W).
     *
     * @param hvac 이 장비가 있는 공간의 이번 틱 냉난방 결과(에어컨 부하율)
     */
    public static double powerW(String typeKey, Map<String, Object> p, ActuatorState s, PhysicsModel.StepResult hvac) {
        double standby = num(p, "standbyW", 1);
        if (!s.on() && !BuiltinCatalog.DOOR_LOCK.equals(typeKey)) {
            return BuiltinCatalog.WINDOW.equals(typeKey) ? 0 : standby;
        }
        return switch (typeKey) {
            case BuiltinCatalog.AIRCON -> {
                double rated = num(p, "ratedPowerW", 1200);
                double load = hvac == null ? 0 : hvac.hvacLoadRatio();
                boolean fanOnly = "fan".equals(s.text("Thermostat", "mode", "off"));
                yield fanOnly ? AIRCON_FAN_W : AIRCON_FAN_W + Math.max(0, rated - AIRCON_FAN_W) * load;
            }
            case BuiltinCatalog.AIR_PURIFIER, BuiltinCatalog.FAN -> num(p, "ratedPowerW", 40) * levelRatio(s);
            case BuiltinCatalog.VENTILATOR -> num(p, "ratedPowerW", 120) * s.number("Ventilation", "level", 1) / 3.0;
            case BuiltinCatalog.HEATER -> num(p, "outputKw", 2) * 1000 * s.number("Dimmer", "level", 100) / 100.0;
            case BuiltinCatalog.LIGHT -> num(p, "ratedPowerW", 200) * s.number("Dimmer", "level", 100) / 100.0;
            case BuiltinCatalog.SMART_PLUG -> num(p, "loadW", 100);
            case BuiltinCatalog.WINDOW -> 0;
            case BuiltinCatalog.DOOR_LOCK -> standby;
            default -> num(p, "ratedPowerW", 0);
        };
    }

    static double levelRatio(ActuatorState s) {
        if (Boolean.TRUE.equals(s.get("FanSpeed", "auto"))) {
            return 2 / 3.0;
        }
        return Math.max(1, Math.min(3, s.number("FanSpeed", "level", 2))) / 3.0;
    }

    public static double num(Map<String, Object> p, String key, double fallback) {
        Object v = p == null ? null : p.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
