package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消控制器：把一个 {@link AgentCancelCause} 传播给流式消费与工具执行。
 * first-cause-wins：第一次 cause 生效，后续 cancel no-op。
 * signal 是 {@link AbortSignal} 的无状态视图（it3 已定型该接口为 seam 词表，
 * executor/stream 消费它——cause 词表经 {@link #describe} 转 String 进
 * TurnEndReason.Aborted，session 不反向依赖 agent）。
 */
public final class AbortController {

    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 取消信号视图（checkAbort 已取消时抛 AbortedException，消息含 cause 描述）。 */
    public AbortSignal signal() {
        return this::throwIfCancelled;
    }

    /**
     * 取消（first-cause-wins；同步块保证 cause 与标志的可见顺序——
     * 读者见 cancelled=true 时 cause 必非 null）。
     *
     * @param cancelCause 取消原因
     */
    public synchronized void cancel(AgentCancelCause cancelCause) {
        Objects.requireNonNull(cancelCause, "cancelCause");
        if (!cancelled.get()) {
            cause.set(cancelCause);
            cancelled.set(true);
        }
    }

    /** 是否已取消。 */
    public boolean isAborted() {
        return cancelled.get();
    }

    /** 生效的取消原因；未取消时 null。 */
    public AgentCancelCause cause() {
        return cause.get();
    }

    private void throwIfCancelled() {
        if (isAborted()) {
            throw new AbortedException(describe(cause()));
        }
    }

    /** cause 的稳定字符串词表（TurnEndReason.Aborted 的 cause 载体）。 */
    static String describe(AgentCancelCause cause) {
        return switch (cause) {
            case AgentCancelCause.User ignored -> "user";
            case AgentCancelCause.Parent ignored -> "parent";
            case AgentCancelCause.Hook hook -> "hook: " + hook.reason();
            case AgentCancelCause.Disposed ignored -> "disposed";
        };
    }
}
