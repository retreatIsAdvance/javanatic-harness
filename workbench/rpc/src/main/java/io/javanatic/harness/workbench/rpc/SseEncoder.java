package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionEventCodec;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** codec 原始载荷 → 单行 JSON；用户数据永不插入 SSE 字段行。 */
final class SseEncoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SessionCodecRegistry codecs;

    SseEncoder(SessionCodecRegistry codecs) {
        this.codecs = Objects.requireNonNull(codecs, "codecs");
    }

    byte[] encode(SseFrame frame) {
        String wire = switch (frame) {
            case SseFrame.Persisted persisted -> {
                var logged = persisted.logged();
                SessionEvent event = logged.event();
                SessionEventCodec<?> codec = codecs.forType(event.type()).orElseThrow(
                    () -> new IllegalStateException("missing SSE codec: " + event.type()));
                ObjectNode envelope = MAPPER.createObjectNode();
                envelope.put("seq", logged.seq());
                envelope.put("type", event.type());
                envelope.put("ignorable", event.ignorable());
                envelope.set("data", json(write(codec, event)));
                yield "id: " + logged.seq() + "\ndata: " + envelope + "\n\n";
            }
            case SseFrame.Control control -> "data: " + json(control.data()) + "\n\n";
        };
        return wire.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] keepalive() {
        return ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
    }

    private static <T extends SessionEvent> JsonValue.Obj write(SessionEventCodec<T> codec, SessionEvent event) {
        return codec.write(codec.typeClass().cast(event));
    }

    private static JsonNode json(JsonValue value) {
        return switch (value) {
            case JsonValue.Obj obj -> {
                ObjectNode object = MAPPER.createObjectNode();
                obj.fields().forEach((key, child) -> object.set(key, json(child)));
                yield object;
            }
            case JsonValue.Arr arr -> {
                ArrayNode array = MAPPER.createArrayNode();
                arr.items().forEach(child -> array.add(json(child)));
                yield array;
            }
            case JsonValue.Str str -> MAPPER.getNodeFactory().textNode(str.value());
            case JsonValue.Num num -> MAPPER.getNodeFactory().numberNode(num.value());
            case JsonValue.Bool bool -> MAPPER.getNodeFactory().booleanNode(bool.value());
            case JsonValue.Null ignored -> MAPPER.getNodeFactory().nullNode();
        };
    }
}
