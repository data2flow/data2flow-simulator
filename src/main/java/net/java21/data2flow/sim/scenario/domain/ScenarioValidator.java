package net.java21.data2flow.sim.scenario.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.common.SimErrorCode;
import net.java21.data2flow.sim.fault.domain.FaultKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 시나리오 검사(SIM-04.01, TC-SIM-040): 기간, 이벤트 시각 범위, 대상 참조, 값 범위, 같은 트랙·대상 구간 겹침.
 * 위반은 {@code SIM_SCENARIO_INVALID}이고 각 오류의 field가 위치(path)다(예: {@code events[3].at}).
 */
public final class ScenarioValidator {

    private ScenarioValidator() {
    }

    /**
     * @param knownSpaces  이 조직의 가상 공간 ID
     * @param knownDevices 이 조직의 가상 기기 ID
     */
    public static void validate(Scenario s, Set<Long> knownSpaces, Set<Long> knownDevices) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (s.name() == null || s.name().isBlank() || s.name().length() > 80) {
            errors.add(err("name", "1~80자"));
        }
        if (s.simStartAt() == null) {
            errors.add(err("simStartAt", "필수"));
        }
        if (s.durationSec() < Scenario.MIN_DURATION_SEC || s.durationSec() > Scenario.MAX_DURATION_SEC) {
            errors.add(err("durationSec", Scenario.MIN_DURATION_SEC + "~" + Scenario.MAX_DURATION_SEC));
        }
        if (s.spaceIds().isEmpty()) {
            errors.add(err("spaceIds", "가상 공간 1개 이상"));
        }
        for (int i = 0; i < s.spaceIds().size(); i++) {
            if (!knownSpaces.contains(s.spaceIds().get(i))) {
                errors.add(err("spaceIds[" + i + "]", "없는 가상 공간"));
            }
        }
        if (s.events().size() > Scenario.MAX_EVENTS) {
            errors.add(err("events", "최대 " + Scenario.MAX_EVENTS + "개"));
        }
        if (s.outdoor() != null && s.outdoor().diurnal() != null && s.outdoor().diurnal().max() < s.outdoor().diurnal().min()) {
            errors.add(err("outdoor.diurnal", "max >= min"));
        }
        Set<String> ids = new HashSet<>();
        List<long[]> occupancyWindows = new ArrayList<>();
        for (int i = 0; i < s.events().size(); i++) {
            ScenarioEvent e = s.events().get(i);
            String p = "events[" + i + "]";
            if (e.id() != null && !ids.add(e.id())) {
                errors.add(err(p + ".id", "중복"));
            }
            if (e.at() == null) {
                errors.add(err(p + ".at", "필수"));
                continue;
            }
            if (s.simStartAt() != null && (e.at().isBefore(s.simStartAt()) || !e.at().isBefore(s.simEndAt()))) {
                errors.add(err(p + ".at", "시나리오 기간 안"));
            }
            if (e.until() != null && !e.until().isAfter(e.at())) {
                errors.add(err(p + ".until", "at 뒤"));
            }
            if (e.track() == null) {
                errors.add(err(p + ".track", "OCCUPANCY|OPENING|ACTUATOR|FAULT"));
                continue;
            }
            switch (e.track()) {
                case ScenarioEvent.OCCUPANCY -> {
                    Long space = e.targetLong("spaceId");
                    if (space == null || !s.spaceIds().contains(space)) {
                        errors.add(err(p + ".target.spaceId", "시나리오 공간"));
                    }
                    double count = e.number("count", -1);
                    if (count < 0 || count > 1000 || count != Math.rint(count)) {
                        errors.add(err(p + ".params.count", "0~1000 정수"));
                    }
                    if (space != null) {
                        long from = e.at().getEpochSecond();
                        long to = e.until() == null ? Long.MAX_VALUE : e.until().getEpochSecond();
                        for (long[] w : occupancyWindows) {
                            if (w[0] == space && from < w[2] && w[1] < to && e.until() != null && w[2] != Long.MAX_VALUE) {
                                errors.add(err(p, "같은 공간 재실 구간이 겹칩니다"));
                                break;
                            }
                        }
                        occupancyWindows.add(new long[]{space, from, to});
                    }
                }
                case ScenarioEvent.OPENING -> {
                    Long space = e.targetLong("spaceId");
                    Long device = e.targetLong("deviceId");
                    if ((space == null || !s.spaceIds().contains(space)) && (device == null || !knownDevices.contains(device))) {
                        errors.add(err(p + ".target", "시나리오 공간 또는 가상 기기"));
                    }
                    Object opening = e.params().get("opening");
                    if (opening != null && !"DOOR".equals(opening) && !"WINDOW".equals(opening)) {
                        errors.add(err(p + ".params.opening", "DOOR|WINDOW"));
                    }
                }
                case ScenarioEvent.ACTUATOR -> {
                    Long device = e.targetLong("deviceId");
                    if (device == null || !knownDevices.contains(device)) {
                        errors.add(err(p + ".target.deviceId", "가상 기기"));
                    }
                    if (!(e.params().get("capability") instanceof String)) {
                        errors.add(err(p + ".params.capability", "필수"));
                    }
                }
                case ScenarioEvent.FAULT -> {
                    Object kind = e.params().get("kind");
                    try {
                        FaultKind.valueOf(String.valueOf(kind));
                    } catch (IllegalArgumentException ex) {
                        errors.add(err(p + ".params.kind", "장애 종류"));
                    }
                    if (!(e.target().get("targetIds") instanceof List<?> l) || l.isEmpty()) {
                        errors.add(err(p + ".target.targetIds", "1개 이상"));
                    }
                    if (e.until() == null && e.number("durationSec", -1) <= 0) {
                        errors.add(err(p + ".until", "until 또는 params.durationSec"));
                    }
                }
                default -> errors.add(err(p + ".track", "OCCUPANCY|OPENING|ACTUATOR|FAULT"));
            }
        }
        for (int i = 0; i < s.expectations().size(); i++) {
            Expectation x = s.expectations().get(i);
            if (x.kind() == null || !List.of("DEVICE_STATE_REACHED", "ALARM_COUNT", "METRIC_RANGE_RATIO", "CONTROL_COUNT_MAX")
                    .contains(x.kind())) {
                errors.add(err("expectations[" + i + "].kind", "DEVICE_STATE_REACHED|ALARM_COUNT|METRIC_RANGE_RATIO|CONTROL_COUNT_MAX"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(SimErrorCode.SIM_SCENARIO_INVALID, errors);
        }
    }

    private static FieldErrorDetail err(String path, String reason) {
        return new FieldErrorDetail(path, SimErrorCode.SIM_SCENARIO_INVALID.code(), reason);
    }
}
