package io.javanatic.harness.llm.openai.compat;

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
 * index→id 配对需要跨事件状态）。真实 wire 中 content/finish_reason/usage
 * 可同现于一个事件（DeepSeek 实测),故每事件产出 0..3 个分块,顺序:
 * 文本/工具增量 → Finish → Usage。非 data 行与 [DONE] 返回空列表；
 * 畸形事件 fail loud（模型信任边界）。
 */
final class SseDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Integer, String> toolCallIds = new HashMap<>();

    /** @return 该事件的分块(0..3 个)；注释/空行/[DONE] 为空列表 */
    java.util.List<StreamChunk> decode(String data) {
        if (data == null || data.isEmpty() || data.startsWith(":") || "[DONE]".equals(data)) {
            return java.util.List.of();
        }
        JsonNode event;
        try {
            event = MAPPER.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException("malformed SSE event: " + data, e);
        }
        java.util.List<StreamChunk> out = new java.util.ArrayList<>(3);
        JsonNode choices = event.get("choices");
        JsonNode choice = choices == null || choices.isEmpty() ? null : choices.get(0);
        JsonNode delta = choice == null ? null : choice.get("delta");
        if (delta != null && delta.hasNonNull("content") && !delta.get("content").asText().isEmpty()) {
            out.add(new StreamChunk.Delta(delta.get("content").asText()));
        }
        JsonNode calls = delta == null ? null : delta.get("tool_calls");
        if (calls != null && !calls.isEmpty()) {
            out.add(toolUse(calls.get(0)));
        }
        if (choice != null && choice.hasNonNull("finish_reason")) {
            out.add(new StreamChunk.Finish(finishReason(choice.get("finish_reason").asText())));
        }
        if (event.hasNonNull("usage")) {
            out.add(new StreamChunk.Usage(usage(event.get("usage"))));
        }
        return out;
    }

    private StreamChunk toolUse(JsonNode call) {
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
