package io.javanatic.harness.agentloop;

import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

import java.util.Locale;
import java.util.Objects;

/**
 * assistant/chunk 的持久化 codec（纯函数：事件 ↔ JsonValue 树）。
 * 经 ServiceLoader 由持久化层发现（module-info provides + META-INF/services）。
 * StreamChunk 属 llm 词表（llm requires session）——codec 家在可见两侧的
 * agent-loop（03 §1 机制 pin），不引入 session 侧第二份 chunk 词表。
 */
public final class AssistantChunkCodec implements SessionEventCodec<AssistantChunkEvent> {

    @Override
    public String type() {
        return "assistant/chunk";
    }

    @Override
    public Class<AssistantChunkEvent> typeClass() {
        return AssistantChunkEvent.class;
    }

    @Override
    public JsonValue.Obj write(AssistantChunkEvent event) {
        return JsonValue.object()
            .set("time", event.time())
            .set("turn", event.turn())
            .set("step", event.step())
            .set("chunk", chunk(event.chunk()))
            .build();
    }

    @Override
    public AssistantChunkEvent read(JsonValue.Obj body) {
        JsonValue.Obj raw = body.get("chunk").asObj();
        if (raw == null) {
            throw new IllegalStateException("assistant/chunk body has no chunk object");
        }
        return new AssistantChunkEvent(body.get("time").asLong(),
            (int) body.get("turn").asLong(), (int) body.get("step").asLong(), chunk(raw));
    }

    private static JsonValue chunk(StreamChunk chunk) {
        return switch (chunk) {
            case StreamChunk.Delta delta -> JsonValue.object()
                .set("kind", "delta").set("text", delta.text()).build();
            case StreamChunk.DeltaToolUse use -> JsonValue.object()
                .set("kind", "tool-use").set("callId", use.id().value()).set("name", use.name())
                .set("argumentsDelta", use.argumentsDelta()).build();
            case StreamChunk.Usage usage -> JsonValue.object()
                .set("kind", "usage").set("input", usage.usage().inputTokens())
                .set("output", usage.usage().outputTokens())
                .set("reasoning", usage.usage().reasoningTokens()).build();
            case StreamChunk.Finish finish -> JsonValue.object()
                .set("kind", "finish").set("reason", wire(finish.reason())).build();
        };
    }

    private static StreamChunk chunk(JsonValue.Obj raw) {
        return switch (raw.get("kind").asString()) {
            case "delta" -> new StreamChunk.Delta(raw.get("text").asString());
            case "tool-use" -> new StreamChunk.DeltaToolUse(CallId.of(raw.get("callId").asString()),
                raw.get("name").asString(), raw.get("argumentsDelta").asString());
            case "usage" -> new StreamChunk.Usage(new TokenUsage(raw.get("input").asLong(),
                raw.get("output").asLong(), raw.get("reasoning").asLong()));
            case "finish" -> new StreamChunk.Finish(finishReason(raw.get("reason").asString()));
            default -> throw new IllegalStateException(
                "unknown assistant/chunk kind: " + raw.get("kind"));
        };
    }

    /** finish_reason 的盘上拼写(lowercase-hyphen;跨版本即格式)。 */
    private static String wire(FinishReason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** @throws IllegalArgumentException 未知拼写(fail loud,与 wire 解析同策) */
    private static FinishReason finishReason(String wire) {
        Objects.requireNonNull(wire, "finish reason");
        return FinishReason.valueOf(wire.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
