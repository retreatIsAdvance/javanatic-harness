package io.javanatic.harness.agent;

import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.UserMessage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 活跃 agent 的公开句柄。UI、编排器、插件都经此操作 agent；
 * 驱动实现在 core/agent-loop 内部（Turn/Step 状态机）。
 */
public interface Agent {

    /** 会话身份（与 session 共享）。 */
    Id<Session> id();

    /** 路由身份（默认 LLM 调用配置的来源）。 */
    AgentOptions options();

    /** 驱动的 live session；其日志是真相源。 */
    Session session();

    /** pending 消息投影。 */
    Inbox inbox();

    /** 驱动状态。 */
    AgentStatus status();

    /** agent-local 注册域（dispose 时回收）。 */
    Scope scope();

    /**
     * 把输入路由到 inbox 边界；wakeup 时确保 driver 在跑。
     *
     * @param message 消息
     * @param target  投递目标
     * @param wakeup  true 唤醒 driver；false 仅排队（inject 语义）
     */
    void send(UserMessage message, InboxTarget target, boolean wakeup);

    /** 排一个普通 follow-up turn 并唤醒；该消息成为自己 turn 的唯一 ordinary 消息。 */
    default void followup(UserMessage message) {
        send(message, InboxTarget.NEXT_TURN, true);
    }

    /** 提交 steering 给最近的 step：running driver 下一步消费，idle driver 开 turn。 */
    default void steer(UserMessage message) {
        send(message, InboxTarget.NEXT_STEP, true);
    }

    /** 排模型可见上下文给下一个 pre-step，不唤醒。 */
    default void inject(UserMessage message) {
        send(message, InboxTarget.NEXT_STEP, false);
    }

    /**
     * 取消活跃 turn 或 between-turn 任务。
     *
     * @param cause   取消原因（first-cause-wins）
     * @param options keepInbox 选择
     */
    void cancel(AgentCancelCause cause, CancelOptions options);

    /** 等当前整个 agent 活动达到静止（driver 退出且无 pending 唤醒）。 */
    CompletableFuture<Void> whenIdle();

    /**
     * 在 true idle 阶段跑一个非 turn 维护任务（compaction、标题生成等）。
     * 任务同步占住 idle 阶段；后来的 waking input 留 inbox，任务 settle 后被驱动。
     *
     * @param task 维护任务（自限时；当前无取消信号——见 04 §实现落定）
     * @param <T>  结果类型
     * @return 任务结果 future
     * @throws IllegalStateException turn 驱动或另一维护任务已占用 agent 时
     */
    <T> CompletableFuture<T> runMaintenance(Supplier<T> task);
}
