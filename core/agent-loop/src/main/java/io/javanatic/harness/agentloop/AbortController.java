package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

/**
 * 取消控制器：把一个 {@link AgentCancelCause} 传播给流式消费与工具执行。
 * first-cause-wins：第一次 cause 生效，后续 cancel no-op。
 * signal 是 {@link AbortSignal} 的无状态视图（it3 已定型该接口为 seam 词表，
 * executor/stream 消费它——cause 词表经 {@link #describe} 转 String 进
 * TurnEndReason.Aborted，session 不反向依赖 agent）。
 */
public final class AbortController {

    private static final System.Logger LOG = System.getLogger(AbortController.class.getName());

    private final AtomicReference<AgentCancelCause> cause = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> cancelActions = new CopyOnWriteArrayList<>();

    /** 取消信号视图（checkAbort 抛取消异常；onCancel 即时击杀类消费者挂监听）。 */
    public AbortSignal signal() {
        return new AbortSignalView();
    }

    private final class AbortSignalView implements AbortSignal {

        @Override
        public void checkAbort() {
            throwIfCancelled();
        }

        @Override
        public void onCancel(Runnable action) {
            AbortController.this.onCancel(action);
        }
    }

    private void onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (isAborted()) {
            action.run();
            return;
        }
        cancelActions.add(action);
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
            // 同步触发已注册动作(如 kill 进程树);动作异常不阻断其余动作与取消语义
            for (Runnable action : cancelActions) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // cancel 动作失败只记录:取消本身已生效,不因单个动作异常中断其余动作
                    LOG.log(System.Logger.Level.WARNING, "cancel action failed", e);
                }
            }
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
