package io.javanatic.harness.session.event;

import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.TokenUsage;

import java.util.List;
import java.util.Objects;

/**
 * 一步的组装后助手消息（派生历史读它）。usage 是适配器报告的 token 计量，
 * 与输出同行（null = 适配器未报告）。空 content 的消息只承载 usage，
 * 投影时产生 null（不进入模型可见历史）。
 */
public record AssistantMessageEvent(
    long time,
    int turn,
    int step,
    AssistantMessage message,
    TokenUsage usage,
    SurfaceOp surfaceOp,
    List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {

    /** @throws NullPointerException message/surfaceOp 为 null 时 */
    public AssistantMessageEvent {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(surfaceOp, "surfaceOp");
        sourceEventSeqs = sourceEventSeqs == null ? null : List.copyOf(sourceEventSeqs);
    }

    @Override public String type() { return "assistant/message"; }
}
