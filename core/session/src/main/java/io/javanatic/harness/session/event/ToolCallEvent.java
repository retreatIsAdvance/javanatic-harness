package io.javanatic.harness.session.event;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.CallId;

import java.util.Objects;

/**
 * 模型请求一次工具调用：name + 原始 arguments JSON（未解析）。
 * 审计对（本事件与 {@link ToolResultEvent}）由 ToolExecutor 无条件落账
 * （R2：执行即留痕）——工具只能经执行下文追加领域事件，碰不到审计对。
 */
public record ToolCallEvent(long time, int turn, int step,
                            Id<CallId> callId, String name, String arguments) implements SessionEvent {

    /** @throws NullPointerException callId/name/arguments 为 null 时 */
    public ToolCallEvent {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }

    @Override public String type() { return "tool/call"; }
}
