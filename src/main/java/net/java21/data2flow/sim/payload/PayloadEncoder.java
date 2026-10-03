package net.java21.data2flow.sim.payload;

import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.DedupKeys;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.random.SimRandom;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가상 센서 payload(SIM-02.04). 실제 디코더(ING-02)가 그대로 해석하는 모양으로 만든다.
 *
 * <ul>
 *   <li><b>CHIRPSTACK_V4</b>: {@code application/{applicationId}/device/{devEui}/event/up} 업링크 이벤트 JSON. deviceInfo(devEui, deviceName,
 *       deviceProfileName, tags), object(측정값), rxInfo[gatewayId, rssi, snr, nsTime], fCnt, deduplicationId. 디코더 {@code chirpstack-v4}.
 *       중복 키 {@code chirpstack:{deduplicationId}}</li>
 *   <li><b>GENERIC_JSON</b>: 토픽 {@code sim/{externalId}/up}, 본문 {@code {"deviceId","time","values":[{"key","value","unit"}]}}.
 *       디코더 {@code generic-json} + {@link #GENERIC_JSON_MAPPING}</li>
 *   <li><b>SINGLE_VALUE</b>: 측정 항목마다 토픽 {@code sim/{externalId}/{metric}} + 숫자 문자열. 디코더 {@code single-value}</li>
 * </ul>
 */
public final class PayloadEncoder {

    /** GENERIC_JSON을 받을 SIM 소스의 generic-json 매핑 설정(ING-02.03) */
    public static final String GENERIC_JSON_MAPPING = "{\"deviceIdFrom\":\"$.deviceId\",\"timeFrom\":\"$.time\","
            + "\"items\":{\"path\":\"$.values[*]\",\"keyFrom\":\"$.key\",\"valueFrom\":\"$.value\",\"unitFrom\":\"$.unit\"}}";

    private static final JsonMapper MAPPER = MessageCodec.newMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private PayloadEncoder() {
    }

    public static List<EncodedUplink> encode(PayloadFormat format, UplinkFrame f, Duration bucketWidth) {
        return switch (format) {
            case CHIRPSTACK_V4 -> List.of(chirpStack(f));
            case GENERIC_JSON -> List.of(genericJson(f));
            case SINGLE_VALUE -> singleValue(f, bucketWidth);
        };
    }

    /** 조직별 가상 ChirpStack 애플리케이션 ID(결정적) */
    public static String applicationId(long organizationId) {
        return SimRandom.uuid(organizationId, "sim-application").toString();
    }

    static EncodedUplink chirpStack(UplinkFrame f) {
        String appId = applicationId(f.organizationId());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("deduplicationId", f.deduplicationId().toString());
        root.put("time", ISO.format(f.measuredAt()));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("tenantId", SimRandom.uuid(f.organizationId(), "sim-tenant").toString());
        info.put("tenantName", "data2flow-simulator");
        info.put("applicationId", appId);
        info.put("applicationName", "data2flow-simulator");
        info.put("deviceProfileId", SimRandom.uuid(f.organizationId(), "sim-profile", f.deviceProfile()).toString());
        info.put("deviceProfileName", f.deviceProfile());
        info.put("deviceName", f.deviceName());
        info.put("devEui", f.externalId());
        info.put("tags", f.tags() == null ? Map.of() : new java.util.TreeMap<>(f.tags()));
        root.put("deviceInfo", info);
        root.put("devAddr", devAddr(f.externalId()));
        root.put("adr", true);
        root.put("dr", 5);
        root.put("fCnt", f.frameCounter());
        root.put("fPort", 85);
        root.put("confirmed", false);
        Map<String, Object> object = new LinkedHashMap<>(f.values());
        // 실제 업링크처럼 data(원본 바이트)도 싣는다. 가상 장비는 코덱이 없으므로 object JSON 바이트를 담는다(디코더는 object를 먼저 쓴다)
        root.put("data", java.util.Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(object)));
        root.put("object", object);
        Map<String, Object> rx = new LinkedHashMap<>();
        rx.put("gatewayId", f.gatewayEui());
        rx.put("uplinkId", (int) (f.frameCounter() & 0x7fffffff));
        rx.put("nsTime", ISO.format(f.measuredAt().plusMillis(15)));
        rx.put("rssi", (int) Math.round(f.rssi()));
        rx.put("snr", Math.round(f.snr() * 4) / 4.0);
        rx.put("channel", (int) (f.frameCounter() % 8));
        rx.put("context", "c2ltdWxhdG9y");
        root.put("rxInfo", List.of(rx));
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("frequency", 922_100_000 + (int) (f.frameCounter() % 8) * 200_000);
        Map<String, Object> lora = new LinkedHashMap<>();
        lora.put("bandwidth", 125_000);
        lora.put("spreadingFactor", 7);
        lora.put("codeRate", "CR_4_5");
        tx.put("modulation", Map.of("lora", lora));
        root.put("txInfo", tx);
        byte[] payload = MAPPER.writeValueAsBytes(root);
        String topic = "application/" + appId + "/device/" + f.externalId() + "/event/up";
        return new EncodedUplink(topic, payload, DedupKeys.chirpStack(f.deduplicationId().toString()));
    }

    static EncodedUplink genericJson(UplinkFrame f) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("deviceId", f.externalId());
        root.put("time", ISO.format(f.measuredAt()));
        root.put("fCnt", f.frameCounter());
        List<Map<String, Object>> values = new ArrayList<>();
        f.values().forEach((k, v) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", k);
            item.put("value", v);
            String unit = f.units() == null ? null : f.units().get(k);
            if (unit != null) {
                item.put("unit", unit);
            }
            values.add(item);
        });
        root.put("values", values);
        byte[] payload = MAPPER.writeValueAsBytes(root);
        String topic = "sim/" + f.externalId() + "/up";
        return new EncodedUplink(topic, payload, DedupKeys.content(f.sourceId(), topic, payload));
    }

    static List<EncodedUplink> singleValue(UplinkFrame f, Duration bucketWidth) {
        List<EncodedUplink> list = new ArrayList<>();
        f.values().forEach((k, v) -> {
            String topic = "sim/" + f.externalId() + "/" + k;
            byte[] payload = number(v).getBytes(StandardCharsets.UTF_8);
            list.add(new EncodedUplink(topic, payload,
                    DedupKeys.contentInBucket(f.sourceId(), topic, payload, f.measuredAt(), bucketWidth)));
        });
        return list;
    }

    /** 잘못된 payload(MALFORMED 장애): JSON을 반쯤 자른다 */
    public static byte[] corrupt(byte[] payload) {
        int n = Math.max(1, payload.length / 2);
        byte[] out = new byte[n + 3];
        System.arraycopy(payload, 0, out, 0, n);
        out[n] = '#';
        out[n + 1] = '!';
        out[n + 2] = '{';
        return out;
    }

    static String number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    static String devAddr(String externalId) {
        String hex = externalId.length() >= 8 ? externalId.substring(externalId.length() - 8) : externalId;
        return hex.toLowerCase();
    }

    /** 수신 시각 기준 버킷 폭(보고 주기의 절반, BR-ING-07) */
    public static Duration bucketWidth(int reportIntervalSec) {
        return Duration.ofMillis(Math.max(1000, reportIntervalSec * 500L));
    }

    /** 측정 시각 문자열(테스트·로그용) */
    public static String iso(Instant at) {
        return ISO.format(at);
    }
}
