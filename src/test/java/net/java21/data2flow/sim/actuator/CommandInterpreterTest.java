package net.java21.data2flow.sim.actuator;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.actuator.domain.CommandInterpreter;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandInterpreterTest {

    static final DeviceTypeDef AIRCON = BuiltinCatalog.type("aircon").orElseThrow();
    static final Map<String, Object> PROPS = PropertyResolver.effective(AIRCON, Map.of(), Map.of());

    static ActuatorState off() {
        return ActuatorState.initial(AIRCON.capabilities());
    }

    @Test
    @DisplayName("[SIM-03.02][AT-SIM-01.2][TC-SIM-032] Thermostat 설정 온도만 보내도 냉방으로 켜지고, Switch off는 모드를 off로")
    void thermostatTurnsOn() {
        ActuatorState s = CommandInterpreter.apply("aircon", PROPS, off(), "Thermostat", "setTargetTemperature",
                Map.of("value", 24));
        assertThat(s.on()).isTrue();
        assertThat(s.get("Thermostat", "mode")).isEqualTo("cool");
        assertThat(s.get("Thermostat", "targetTemperature")).isEqualTo(24.0);
        ActuatorState offAgain = CommandInterpreter.apply("aircon", PROPS, s, "Switch", "off", Map.of());
        assertThat(offAgain.on()).isFalse();
        assertThat(offAgain.get("Thermostat", "mode")).isEqualTo("off");
        ActuatorState heat = CommandInterpreter.apply("aircon", PROPS, offAgain, "Thermostat", "set", Map.of("mode", "heat"));
        assertThat(heat.on()).isTrue();
        ActuatorState onAgain = CommandInterpreter.apply("aircon", PROPS,
                CommandInterpreter.apply("aircon", PROPS, heat, "Switch", "set", Map.of("on", false)), "Switch", "on", Map.of());
        assertThat(onAgain.get("Thermostat", "mode")).isEqualTo("heat");
        // 목표 상태 설정이라 두 번 적용해도 같다
        assertThat(CommandInterpreter.apply("aircon", PROPS, s, "Thermostat", "set", Map.of("targetTemperature", 24)).reported())
                .isEqualTo(s.reported());
    }

    @Test
    @DisplayName("[SIM-03.02][AT-SIM-01.2][TC-SIM-032] 범위 밖 값(설정 온도 40℃)·지원하지 않는 기능·명령 → 400 INVALID_REQUEST")
    void rejects() {
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "Thermostat", "set", Map.of("targetTemperature", 40)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrors().get(0).field()).isEqualTo("args.targetTemperature"));
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "Dimmer", "set", Map.of("level", 3)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "Switch", "toggle", Map.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "FanSpeed", "set", Map.of("level", 9)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "Thermostat", "set", Map.of("mode", "turbo")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "Switch", "set", Map.of("on", "yes")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandInterpreter.apply("aircon", PROPS, off(), "Thermostat", "set", Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[SIM-03.02][AT-SIM-01.2][TC-SIM-032] 환기·조명·도어락·가습기·선풍기 명령")
    void otherCapabilities() {
        DeviceTypeDef vent = BuiltinCatalog.type("ventilator").orElseThrow();
        ActuatorState v = CommandInterpreter.apply("ventilator", Map.of(), ActuatorState.initial(vent.capabilities()), "Ventilation",
                "set", Map.of("mode", "on", "level", 3));
        assertThat(v.on()).isTrue();
        assertThat(v.get("Ventilation", "level")).isEqualTo(3);
        ActuatorState vOff = CommandInterpreter.apply("ventilator", Map.of(), v, "Switch", "off", Map.of());
        assertThat(vOff.get("Ventilation", "mode")).isEqualTo("off");
        assertThat(CommandInterpreter.apply("ventilator", Map.of(), vOff, "Switch", "on", Map.of()).get("Ventilation", "mode"))
                .isEqualTo("on");

        DeviceTypeDef light = BuiltinCatalog.type("light").orElseThrow();
        ActuatorState l = CommandInterpreter.apply("light", Map.of(), ActuatorState.initial(light.capabilities()), "Dimmer", "setLevel",
                Map.of("value", 60));
        assertThat(l.on()).isTrue();
        assertThat(CommandInterpreter.apply("light", Map.of(), l, "Dimmer", "set", Map.of("level", 0)).on()).isFalse();

        DeviceTypeDef lock = BuiltinCatalog.type("door-lock").orElseThrow();
        assertThat(CommandInterpreter.apply("door-lock", Map.of(), ActuatorState.initial(lock.capabilities()), "Lock", "unlock", Map.of())
                .get("Lock", "locked")).isEqualTo(false);

        DeviceTypeDef hum = BuiltinCatalog.type("humidifier").orElseThrow();
        assertThat(CommandInterpreter.apply("humidifier", Map.of(), ActuatorState.initial(hum.capabilities()), "custom.Humidifier",
                "setTargetHumidity", Map.of("value", 55)).get("custom.Humidifier", "targetHumidity")).isEqualTo(55);

        DeviceTypeDef fan = BuiltinCatalog.type("fan").orElseThrow();
        ActuatorState f = CommandInterpreter.apply("fan", Map.of(), ActuatorState.initial(fan.capabilities()), "FanSpeed", "set",
                Map.of("level", 3, "auto", false));
        assertThat(f.on()).isTrue();
        assertThat(CommandInterpreter.apply("fan", Map.of(), f, "FanSpeed", "set", Map.of("level", 0)).on()).isFalse();
    }
}
