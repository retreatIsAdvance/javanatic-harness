package io.javanatic.harness.agentloop;

import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.event.ExtensionEvent;

import java.util.Objects;

/**
 * 模型流式分块（log-only 事实，03 §1）：逐 chunk 落账，供交互面流式渲染与
 * TTFT/遥测消费。不参与模型可见投影——装配仍走 assistant/message。
 * ignorable=true：信息性事件，未知读取方跳过不改变重建语义。
 */
public record AssistantChunkEvent(long time, int turn, int step, StreamChunk chunk)
        implements ExtensionEvent {

    /** @throws NullPointerException chunk 为 null 时 */
    public AssistantChunkEvent {
        Objects.requireNonNull(chunk, "chunk");
    }

    @Override
    public String type() {
        return "assistant/chunk";
    }

    @Override
    public boolean ignorable() {
        return true;
    }
}
