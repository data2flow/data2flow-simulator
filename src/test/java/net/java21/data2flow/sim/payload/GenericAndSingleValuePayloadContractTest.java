package net.java21.data2flow.sim.payload;

import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.engine.Emission;
import net.java21.data2flow.sim.support.Worlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenericAndSingleValuePayloadContractTest {

    @Test
    @DisplayName("[SIM-02.04][AT-SIM-05.5][TC-SIM-022] generic JSON → 매핑(deviceId·time·values[*])으로 읽히는 모양, 내용 해시 중복 키")
    void genericJson() {
        Worlds w = Worlds.classroom().hours(0.25)
                .device(Worlds.dev(1, "온습도", "th-sensor").interval(60).format(PayloadFormat.GENERIC_JSON).build());
        List<Emission.Uplink> ups = Worlds.uplinks(Worlds.runToEnd(w.world()));
        assertThat(ups).isNotEmpty();
        JsonNode mapping = MessageCodec.newMapper().readTree(PayloadEncoder.GENERIC_JSON_MAPPING);
        assertThat(mapping.get("deviceIdFrom").asString()).isEqualTo("$.deviceId");
        for (Emission.Uplink u : ups) {
            JsonNode n = MessageCodec.newMapper().readTree(u.payload());
            assertThat(u.topic()).isEqualTo("sim/5a1d000000000001/up");
            assertThat(n.get("deviceId").asString()).isEqualTo("5a1d000000000001");
            assertThat(n.get("time").asString()).endsWith("Z");
            assertThat(n.get("values").size()).isEqualTo(3);
            assertThat(n.get("values").get(0).get("key").asString()).isEqualTo("temperature");
            assertThat(n.get("values").get(0).get("unit").asString()).isEqualTo("℃");
            assertThat(u.dedupKey()).startsWith("sha256:");
        }
    }

    @Test
    @DisplayName("[SIM-02.04][AT-SIM-05.5][TC-SIM-022] single-value → 토픽 sim/{external-id}/{metric} + 숫자 문자열, 버킷 중복 키")
    void singleValue() {
        Worlds w = Worlds.classroom().hours(0.25)
                .device(Worlds.dev(1, "온습도", "th-sensor").interval(60).format(PayloadFormat.SINGLE_VALUE).build());
        List<Emission.Uplink> ups = Worlds.uplinks(Worlds.runToEnd(w.world()));
        assertThat(ups.size() % 3).isZero();
        for (Emission.Uplink u : ups) {
            assertThat(u.topic()).matches("sim/5a1d000000000001/(temperature|humidity|battery)");
            String text = new String(u.payload(), StandardCharsets.UTF_8);
            assertThat(text).matches("[+-]?\\d+(\\.\\d+)?");
            assertThat(u.dedupKey()).startsWith("sha256b:");
        }
    }

    @Test
    @DisplayName("[SIM-05.02][AT-SIM-11.5][TC-SIM-063] 잘못된 payload는 JSON으로 읽히지 않는다")
    void corrupt() {
        byte[] bad = PayloadEncoder.corrupt("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(bad, StandardCharsets.UTF_8)).endsWith("#!{");
        assertThat(PayloadEncoder.number(3.0)).isEqualTo("3");
        assertThat(PayloadEncoder.number(3.25)).isEqualTo("3.25");
        assertThat(PayloadFormat.ofDecoderKey("generic-json")).isEqualTo(PayloadFormat.GENERIC_JSON);
        assertThat(PayloadFormat.ofDecoderKey("single-value")).isEqualTo(PayloadFormat.SINGLE_VALUE);
        assertThat(PayloadFormat.ofDecoderKey("chirpstack-v4")).isEqualTo(PayloadFormat.CHIRPSTACK_V4);
        assertThat(PayloadFormat.ofDecoderKey("x")).isNull();
        assertThat(PayloadFormat.ofDecoderKey(null)).isNull();
    }
}
