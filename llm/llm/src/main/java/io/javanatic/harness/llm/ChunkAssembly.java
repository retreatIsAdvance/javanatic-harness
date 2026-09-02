package io.javanatic.harness.llm;
import io.javanatic.harness.session.message.CallId;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * chunk 流的组装纯函数：折叠为一步的最终形态。回放测试用它把脚本折叠成
 * 期望消息，agent-loop 用它把真实流折叠成 AssistantMessageEvent——
 * 两处必须走同一函数（否则回放验证的不是生产行为）。
 */
public final class ChunkAssembly {

    /** 组装后的一个工具调用（arguments 已按增量拼接为完整 JSON 字符串）。 */
    public record ToolCall(Id<CallId> id, String name, String arguments) {}

    /** 一步的组装结果。text 为空串表示无文本输出（纯工具调用步）。 */
    public record Assembled(String text, List<ToolCall> toolCalls,
                            TokenUsage usage, FinishReason finishReason) {}

    private ChunkAssembly() {
    }

    /**
     * @throws IllegalArgumentException 缺 Finish、Finish 后仍有分块、
     *         或同一 id 的 tool-use 分块携带冲突的 name
     */
    public static Assembled fold(Iterable<StreamChunk> chunks) {
        StringBuilder text = new StringBuilder();
        Map<Id<CallId>, ToolBuilder> calls = new LinkedHashMap<>();
        TokenUsage usage = null;
        FinishReason finish = null;
        for (StreamChunk chunk : chunks) {
            if (finish != null) {
                throw new IllegalArgumentException("chunk after Finish (" + finish + ")");
            }
            switch (chunk) {
                case StreamChunk.Delta d -> text.append(d.text());
                case StreamChunk.DeltaToolUse t -> {
                    ToolBuilder builder = calls.computeIfAbsent(t.id(), k -> new ToolBuilder());
                    builder.append(t.name(), t.argumentsDelta());
                }
                case StreamChunk.Usage u -> usage = u.usage();
                case StreamChunk.Finish f -> finish = f.reason();
            }
        }
        if (finish == null) {
            throw new IllegalArgumentException("stream ended without a Finish chunk");
        }
        List<ToolCall> assembled = new ArrayList<>();
        for (Map.Entry<Id<CallId>, ToolBuilder> entry : calls.entrySet()) {
            assembled.add(entry.getValue().build(entry.getKey()));
        }
        return new Assembled(text.toString(), List.copyOf(assembled), usage, finish);
    }

    /** 同一工具调用的增量累积器：name 以首次出现为准，冲突即 fail loud。 */
    private static final class ToolBuilder {
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void append(String chunkName, String argumentsDelta) {
            if (chunkName != null && !chunkName.isEmpty()) {
                if (name == null) {
                    name = chunkName;
                } else if (!name.equals(chunkName)) {
                    throw new IllegalArgumentException(
                        "tool-use chunks disagree on name: '" + name + "' vs '" + chunkName + "'");
                }
            }
            if (argumentsDelta != null) {
                arguments.append(argumentsDelta);
            }
        }

        ToolCall build(Id<CallId> id) {
            Objects.requireNonNull(name, "name");
            return new ToolCall(id, name, arguments.toString());
        }
    }
}
