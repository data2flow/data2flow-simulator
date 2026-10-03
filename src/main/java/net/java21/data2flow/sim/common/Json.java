package net.java21.data2flow.sim.common;

import net.java21.data2flow.contracts.message.MessageCodec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** jsonb 열 읽고 쓰기(contracts MessageCodec 매퍼: ISO-8601 시각, 모르는 필드 무시) */
public final class Json {

    public static final JsonMapper MAPPER = MessageCodec.newMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private Json() {
    }

    public static String write(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public static <T> T read(String json, TypeReference<T> type) {
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public static Map<String, Object> map(String json) {
        return json == null ? new LinkedHashMap<>() : MAPPER.readValue(json, MAP);
    }

    public static Map<String, Object> map(JsonNode node) {
        return node == null || node.isNull() ? new LinkedHashMap<>() : MAPPER.convertValue(node, MAP);
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }
}
