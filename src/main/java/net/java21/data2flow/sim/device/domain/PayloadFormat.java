package net.java21.data2flow.sim.device.domain;

/** 가상 센서 payload 형식(SIM-02.04). 디코더 키와 같은 이름 짝: chirpstack-v4·generic-json·single-value */
public enum PayloadFormat {
    CHIRPSTACK_V4, GENERIC_JSON, SINGLE_VALUE;

    /** pipeline 디코더 키(contracts {@code DecoderKeys})에 대응하는 형식. 모르면 null */
    public static PayloadFormat ofDecoderKey(String decoderKey) {
        if (decoderKey == null) {
            return null;
        }
        return switch (decoderKey) {
            case "chirpstack-v4" -> CHIRPSTACK_V4;
            case "generic-json" -> GENERIC_JSON;
            case "single-value" -> SINGLE_VALUE;
            default -> null;
        };
    }
}
