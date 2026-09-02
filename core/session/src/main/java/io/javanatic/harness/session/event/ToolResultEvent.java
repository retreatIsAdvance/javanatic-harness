package io.javanatic.harness.session.event;

import io.javanatic.harness.session.message.ToolResultBlock;

import java.util.List;
import java.util.Objects;

/**
 * 一次工具调用的模型可见结果。由 ToolExecutor 的 pipeline 落账（R2 单一路径），
 * 投影为 UserMessage(source=Tool, [ToolResultBlock])。concludesTurn 标记该结果
 * 是否终结本 turn（ask_user 类交互工具为 true；本迭代工具恒 false）。
 */
public record ToolResultEvent(long time, int turn, int step,
                              ToolResultBlock block, boolean concludesTurn,
                              SurfaceOp surfaceOp, List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {

    /** @throws NullPointerException block/surfaceOp 为 null 时 */
    public ToolResultEvent {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(surfaceOp, "surfaceOp");
        sourceEventSeqs = sourceEventSeqs == null ? null : List.copyOf(sourceEventSeqs);
    }

    @Override public String type() { return "tool/result"; }
}
