package net.java21.data2flow.sim.payload;

import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.RawEnvelope;
import net.java21.data2flow.contracts.messaging.DedupKeys;
import net.java21.data2flow.contracts.test.message.MessageFixtures;
import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ChirpStackPayloadContractTest {

    private static final Pattern TOPIC = Pattern.compile("application/[^/]+/device/([0-9a-fA-F]{16})/event/up");

    @Test
    @DisplayName("[SIM-02.04][AT-SIM-01.4][TC-SIM-021] ChirpStack v4 업링크가 골든 픽스처와 같은 구조, fCnt 1씩 증가, rssi -120~-30·snr -20~15")
    void chirpStackShape() {
        Worlds w = Worlds.classroom().hours(1).device(Worlds.dev(1, "EM500-CO2-01", "em500-co2").interval(60).build());
        SimulationWorld world = w.world();
        List<Emission.Uplink> ups = Worlds.uplinks(Worlds.runToEnd(world));
        assertThat(ups).hasSizeGreaterThanOrEqualTo(59);
        JsonNode golden = MessageCodec.newMapper().readTree(MessageFixtures.chirpStackUplinkPayload());
        Set<String> goldenKeys = keys(golden);
        long prevFcnt = -1;
        for (Emission.Uplink u : ups) {
            JsonNode n = MessageCodec.newMapper().readTree(u.payload());
            assertThat(keys(n)).containsAll(goldenKeys);
            assertThat(keys(n.get("deviceInfo"))).containsAll(keys(golden.get("deviceInfo")));
            assertThat(n.get("deviceInfo").get("devEui").asString()).isEqualTo("5a1d000000000001");
            assertThat(n.get("object").has("co2")).isTrue();
            assertThat(n.get("object").has("pressure")).isTrue();
            JsonNode rx = n.get("rxInfo").get(0);
            assertThat(keys(rx)).contains("gatewayId", "rssi", "snr", "nsTime");
            assertThat(rx.get("rssi").asDouble()).isBetween(-120.0, -30.0);
            assertThat(rx.get("snr").asDouble()).isBetween(-20.0, 15.0);
            long fCnt = n.get("fCnt").asLong();
            if (prevFcnt >= 0) {
                assertThat(fCnt).isEqualTo(prevFcnt + 1);
            }
            prevFcnt = fCnt;
            assertThat(TOPIC.matcher(u.topic()).matches()).isTrue();
            // 수집 경로의 중복 키 판정과 같다(chirpstack:{deduplicationId})
            assertThat(DedupKeys.detect(u.sourceId(), u.topic(), u.payload())).isEqualTo(u.dedupKey());
            assertThat(u.dedupKey()).startsWith("chirpstack:");
            RawEnvelope env = new RawEnvelope(1, u.messageId(), u.organizationId(), u.sourceId(), "SIMULATION", u.topic(),
                    u.payload(), u.receivedAt(), "data2flow-simulator-0", u.dedupKey(), true, 7L);
            assertThat(env.routingKey()).isNotBlank();
        }
    }

    static Set<String> keys(JsonNode n) {
        Set<String> s = new TreeSet<>();
        n.properties().forEach(e -> s.add(e.getKey()));
        return s;
    }

    @Test
    @DisplayName("[SIM-02.04][AT-SIM-01.4][TC-SIM-021] 같은 입력이면 payload 바이트가 같다(재생성분은 중복 제거 대상)")
    void bytesDeterministic() {
        List<byte[]> a = payloads();
        List<byte[]> b = payloads();
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i)).isEqualTo(b.get(i));
        }
    }

    private static List<byte[]> payloads() {
        Worlds w = Worlds.classroom().hours(0.5).device(Worlds.dev(1, "AM107-01", "am107").interval(60).build());
        List<byte[]> list = new ArrayList<>();
        Worlds.uplinks(Worlds.runToEnd(w.world())).forEach(u -> list.add(u.payload()));
        return list;
    }
}
