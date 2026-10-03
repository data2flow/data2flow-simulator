package net.java21.data2flow.sim.fault;

import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InfraFaultTest {

    static final Instant FROM = Worlds.START.plusSeconds(1800);
    static final Instant TO = FROM.plusSeconds(1800);

    static List<Emission.Uplink> run(FaultKind kind, String targetType, String target, Map<String, Object> params, double hours) {
        Worlds w = Worlds.classroom().hours(hours);
        for (int i = 1; i <= 5; i++) {
            w.device(Worlds.dev(i, "센서-" + i, "th-sensor").interval(60).gateway(i <= 3 ? "gw-a" : "gw-b").build());
        }
        w.fault(new FaultSpec(21, kind, targetType, target, params, FROM, TO));
        return Worlds.uplinks(Worlds.runToEnd(w.world()));
    }

    static boolean inside(Emission.Uplink u) {
        return !u.measuredAt().isBefore(FROM) && u.measuredAt().isBefore(TO);
    }

    @Test
    @DisplayName("[SIM-05.02][AT-SIM-09.3][TC-SIM-064] GATEWAY_DOWN 30분 → 소속 센서 보고 0, 다른 게이트웨이는 정상, 해제 뒤 재개")
    void gatewayDown() {
        List<Emission.Uplink> ups = run(FaultKind.GATEWAY_DOWN, FaultSpec.GATEWAY, "gw-a", Map.of(), 1.5);
        assertThat(ups.stream().filter(InfraFaultTest::inside).filter(u -> u.deviceId() <= 3)).isEmpty();
        assertThat(ups.stream().filter(InfraFaultTest::inside).filter(u -> u.deviceId() > 3)).isNotEmpty();
        assertThat(ups.stream().filter(u -> !u.measuredAt().isBefore(TO)).filter(u -> u.deviceId() <= 3)).isNotEmpty();
    }

    @Test
    @DisplayName("[SIM-05.02][AT-SIM-11.3][TC-SIM-061] DUPLICATE ×2 → 같은 dedupKey 2건(messageId는 다름)")
    void duplicate() {
        List<Emission.Uplink> ups = run(FaultKind.DUPLICATE, FaultSpec.DEVICE, "1", Map.of("factor", 2), 1.5);
        Map<String, List<Emission.Uplink>> byKey = ups.stream().filter(u -> u.deviceId() == 1).filter(InfraFaultTest::inside)
                .collect(Collectors.groupingBy(Emission.Uplink::dedupKey));
        assertThat(byKey).isNotEmpty().allSatisfy((k, v) -> {
            assertThat(v).hasSize(2);
            assertThat(v.get(0).messageId()).isNotEqualTo(v.get(1).messageId());
            assertThat(v.get(0).payload()).isEqualTo(v.get(1).payload());
        });
    }

    @Test
    @DisplayName("[SIM-05.02][AT-SIM-11.4][TC-SIM-062] DELAY 2시간 → 수신 시각이 측정 시각보다 2시간 늦다")
    void delay() {
        List<Emission.Uplink> ups = run(FaultKind.DELAY, FaultSpec.DEVICE, "1", Map.of("delaySec", 7200), 3);
        List<Emission.Uplink> delayed = ups.stream().filter(u -> u.deviceId() == 1).filter(InfraFaultTest::inside).toList();
        assertThat(delayed).isNotEmpty().allSatisfy(u ->
                assertThat(Duration.between(u.measuredAt(), u.receivedAt()).toSeconds()).isBetween(7200L, 7210L));
    }

    @Test
    @DisplayName("[SIM-05.02][AT-SIM-11.4][TC-SIM-062] REORDER(창 5분) → 보내는 순서가 측정 시각과 반대")
    void reorder() {
        List<Emission.Uplink> ups = run(FaultKind.REORDER, FaultSpec.DEVICE, "1", Map.of("windowSec", 300), 1.5).stream()
                .filter(u -> u.deviceId() == 1).toList();
        boolean descending = false;
        for (int i = 1; i < ups.size(); i++) {
            if (ups.get(i).measuredAt().isBefore(ups.get(i - 1).measuredAt())) {
                descending = true;
            }
        }
        assertThat(descending).isTrue();
    }

    @Test
    @DisplayName("[SIM-05.02][AT-SIM-11.5][TC-SIM-063] MALFORMED 50% → 대략 절반이 JSON이 아니다")
    void malformed() {
        List<Emission.Uplink> in = run(FaultKind.MALFORMED, FaultSpec.DEVICE, "1", Map.of("ratio", 0.5), 1.5).stream()
                .filter(u -> u.deviceId() == 1).filter(InfraFaultTest::inside).toList();
        long broken = in.stream().filter(u -> new String(u.payload()).endsWith("#!{")).count();
        assertThat(broken).isBetween(5L, 25L);
        assertThat(in.stream().filter(u -> new String(u.payload()).endsWith("#!{"))).allMatch(u -> u.dedupKey().startsWith("sha256:"));
    }
}
