package io.javanatic.harness.llm.deepseek;

import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.TokenUsage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE 事件 → {@link StreamChunk} 解码（每流一个实例——tool_calls 增量按
 * index→id 配对需要跨事件状态）。非 data 行与 [DONE] 返回 null；
 * 畸形事件 fail loud（模型信任边界）。
 */
final class SseDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Integer, String> toolCallIds = new HashMap<>();

    /** @return 对应分块；注释/空行/[DONE] 为 null */
    StreamChunk decode(String data) {
        if (data == null || data.isEmpty() || data.startsWith(":") || "[DONE]".equals(data)) {
            return null;
        }
        JsonNode event;
        try {
            event = MAPPER.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException("malformed SSE event: " + data, e);
        }
        if (event.hasNonNull("usage")) {
            return new StreamChunk.Usage(usage(event.get("usage")));
        }
        JsonNode choices = event.get("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");
        if (delta != null && delta.hasNonNull("content")) {
            return new StreamChunk.Delta(delta.get("content").asText());
        }
        JsonNode calls = delta == null ? null : delta.get("tool_calls");
        if (calls != null && !calls.isEmpty()) {
            JsonNode call = calls.get(0);
            int index = call.path("index").asInt(0);
            String id = call.hasNonNull("id") ? call.get("id").asText() : toolCallIds.get(index);
            if (id == null) {
                throw new IllegalStateException("tool_call delta without preceding id at index " + index);
            }
            toolCallIds.put(index, id);
            String name = call.path("function").path("name").isTextual()
                ? call.get("function").get("name").asText() : null;
            String argumentsDelta = call.path("function").path("arguments").isTextual()
                ? call.get("function").get("arguments").asText() : "";
            return new StreamChunk.DeltaToolUse(CallId.of(id), name, argumentsDelta);
        }
        if (choice.hasNonNull("finish_reason")) {
            return new StreamChunk.Finish(finishReason(choice.get("finish_reason").asText()));
        }
        return null;
    }

    private static TokenUsage usage(JsonNode usage) {
        return new TokenUsage(
            usage.path("prompt_tokens").asLong(0),
            usage.path("completion_tokens").asLong(0),
            usage.path("reasoning_tokens").asLong(0));
    }

    private static FinishReason finishReason(String reason) {
        return switch (reason) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls" -> FinishReason.TOOL_USE;
            case "length" -> FinishReason.LENGTH;
            default -> throw new IllegalStateException("unknown finish_reason: " + reason);
        };
    }

}
