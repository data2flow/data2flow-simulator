package net.java21.data2flow.sim.scenario;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.Scenario;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import net.java21.data2flow.sim.scenario.domain.ScenarioValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ScenarioValidatorTest {

    static final Instant T = Instant.parse("2026-08-10T00:00:00Z");

    static Scenario scenario(List<ScenarioEvent> events) {
        return new Scenario("폭염 오후", List.of(100L), T, 86_400, 42L, false, OutdoorSpec.diurnal(35, 27, 15, 55), events,
                List.of(new Expectation("x1", "DEVICE_STATE_REACHED", Map.of("deviceId", 10), Map.of("power", "ON"), null)), null);
    }

    @Test
    @DisplayName("[SIM-04.01][AT-SIM-07.1][TC-SIM-040] 올바른 시나리오는 통과한다")
    void valid() {
        assertThatCode(() -> ScenarioValidator.validate(scenario(List.of(
                new ScenarioEvent("e1", "OCCUPANCY", T.plusSeconds(3600), T.plusSeconds(7200), Map.of("spaceId", 100), Map.of("count", 30)),
                new ScenarioEvent("e2", "OPENING", T.plusSeconds(3600), null, Map.of("spaceId", 100), Map.of("opening", "WINDOW", "open", true)),
                new ScenarioEvent("e3", "ACTUATOR", T.plusSeconds(60), null, Map.of("deviceId", 10), Map.of("capability", "Switch",
                        "command", "on")),
                new ScenarioEvent("e4", "FAULT", T.plusSeconds(60), null, Map.of("targetType", "DEVICE", "targetIds", List.of("1")),
                        Map.of("kind", "STUCK", "durationSec", 1800)))), Set.of(100L), Set.of(1L, 10L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[SIM-04.01][AT-SIM-07.1][TC-SIM-040] 기간 밖 시각·없는 공간·기기, 재실 음수, 구간 겹침 → SIM_SCENARIO_INVALID(위치 포함)")
    void invalid() {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> ScenarioValidator.validate(scenario(List.of(
                new ScenarioEvent("e1", "OCCUPANCY", T.minusSeconds(10), null, Map.of("spaceId", 100), Map.of("count", 3)),
                new ScenarioEvent("e2", "OCCUPANCY", T.plusSeconds(10), null, Map.of("spaceId", 999), Map.of("count", -1)),
                new ScenarioEvent("e3", "ACTUATOR", T.plusSeconds(10), null, Map.of("deviceId", 77), Map.of()),
                new ScenarioEvent("e4", "OCCUPANCY", T.plusSeconds(100), T.plusSeconds(500), Map.of("spaceId", 100), Map.of("count", 3)),
                new ScenarioEvent("e5", "OCCUPANCY", T.plusSeconds(200), T.plusSeconds(600), Map.of("spaceId", 100), Map.of("count", 3)),
                new ScenarioEvent("e6", "FAULT", T.plusSeconds(10), null, Map.of(), Map.of("kind", "NOPE")),
                new ScenarioEvent("e7", "WEIRD", T.plusSeconds(10), null, Map.of(), Map.of()),
                new ScenarioEvent("e7", "OPENING", T.plusSeconds(10), T, Map.of(), Map.of("opening", "ROOF")))),
                Set.of(100L), Set.of(10L)));
        assertThat(e.getErrorCode()).isEqualTo(SimErrorCode.SIM_SCENARIO_INVALID);
        assertThat(e.getErrors().stream().map(FieldErrorDetail::field)).contains("events[0].at", "events[1].target.spaceId",
                "events[1].params.count", "events[2].target.deviceId", "events[2].params.capability", "events[4]",
                "events[5].params.kind", "events[5].target.targetIds", "events[5].until", "events[6].track", "events[7].id",
                "events[7].until", "events[7].target", "events[7].params.opening");
    }

    @Test
    @DisplayName("[SIM-04.01][AT-SIM-07.1][TC-SIM-040] 이름·기간·공간·기대 결과 종류 검사")
    void header() {
        Scenario bad = new Scenario("", List.of(), T, 10, null, false, null, List.of(),
                List.of(new Expectation("x", "NOPE", null, null, null)), null);
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> ScenarioValidator.validate(bad, Set.of(), Set.of()));
        assertThat(e.getErrors().stream().map(FieldErrorDetail::field)).contains("name", "durationSec", "spaceIds", "expectations[0].kind");
    }
}
