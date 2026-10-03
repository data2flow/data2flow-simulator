package net.java21.data2flow.sim.payload;

/**
 * 형식에 맞춰 만든 원본 한 건.
 *
 * @param topic    RawEnvelope.topic(ChirpStack 토픽 모양 등)
 * @param payload  원본 바이트
 * @param dedupKey 중복 판정 키(contracts DedupKeys)
 */
public record EncodedUplink(String topic, byte[] payload, String dedupKey) {
}
