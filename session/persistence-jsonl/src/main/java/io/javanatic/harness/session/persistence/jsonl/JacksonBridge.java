package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.session.persistence.JsonValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Jackson ↔ JsonValue 互译(JSON 边界在本模块,seam 零 Jackson)。 */
final class JacksonBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JacksonBridge() {
    }

    /** @return JsonValue 树的事件体字符串(紧凑 JSON) */
    static String write(JsonValue value) {
        return toNode(value).toString();
    }

    /** @return 解析事件体字符串为 JsonValue 对象(非对象/畸形 fail loud) */
    static JsonValue.Obj read(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("event body is not a JSON object: " + json);
            }
            return (JsonValue.Obj) fromNode(node);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("event body is not valid JSON", e);
        }
    }

    static JsonNode toNode(JsonValue value) {
        return switch (value) {
            case JsonValue.Obj obj -> {
                ObjectNode node = MAPPER.createObjectNode();
                obj.fields().forEach((field, child) -> node.set(field, toNode(child)));
                yield node;
            }
            case JsonValue.Arr arr -> {
                ArrayNode node = MAPPER.createArrayNode();
                arr.items().forEach(item -> node.add(toNode(item)));
                yield node;
            }
            case JsonValue.Str str -> MAPPER.getNodeFactory().textNode(str.value());
            case JsonValue.Num num -> MAPPER.getNodeFactory().numberNode(num.value());
            case JsonValue.Bool bool -> MAPPER.getNodeFactory().booleanNode(bool.value());
            case JsonValue.Null ignored -> MAPPER.getNodeFactory().nullNode();
        };
    }

    static JsonValue fromNode(JsonNode node) {
        if (node.isObject()) {
            JsonValue.Builder builder = JsonValue.object();
            node.fields().forEachRemaining(entry ->
                builder.set(entry.getKey(), fromNode(entry.getValue())));
            return builder.build();
        }
        if (node.isArray()) {
            JsonValue[] items = new JsonValue[node.size()];
            for (int i = 0; i < node.size(); i++) {
                items[i] = fromNode(node.get(i));
            }
            return new JsonValue.Arr(List.of(items));
        }
        if (node.isTextual()) {
            return new JsonValue.Str(node.asText());
        }
        if (node.isBoolean()) {
            return new JsonValue.Bool(node.asBoolean());
        }
        if (node.isNumber()) {
            return new JsonValue.Num(node.asLong());
        }
        return JsonValue.Null.INSTANCE;
    }
}
