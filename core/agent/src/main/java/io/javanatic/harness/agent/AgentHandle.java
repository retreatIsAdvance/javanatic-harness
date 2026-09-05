package io.javanatic.harness.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个被拥有的 agent + 其 dispose 能力。dispose 是 capability：只有持有
 * handle 的消费者能 teardown agent，且必须显式触发——工厂绝不构造期启动
 * teardown（创建即 cancel 会清空 inbox，与首个 turn 竞态）。
 * dispose 链：cancel(Disposed) → 等 driver 静止 → 回收 agent scope
 * （注销由 Registry 组合在链尾）。
 *
 * @param agent   被拥有的 agent
 * @param disposer 单次触发的 teardown 能力
 */
public record AgentHandle(Agent agent, Disposer disposer) {

    /** @throws NullPointerException 任一字段为 null 时 */
    public AgentHandle {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(disposer, "disposer");
    }

    /** teardown 能力：触发 dispose 链并返回其 future。 */
    @FunctionalInterface
    public interface Disposer {

        /** @return teardown 链 future（幂等：重复触发返回同一 future） */
        CompletableFuture<Void> dispose();
    }

    /** 触发 dispose 链（幂等）。 */
    public CompletableFuture<Void> dispose() {
        return disposer.dispose();
    }

    /** 触发并阻塞等待完成（虚拟线程上调用）。 */
    public void disposeAndAwait() {
        dispose().join();
    }

    /**
     * 单次触发包装：底层链只执行一次，重复 dispose 返回同一 future。
     *
     * @param chain 底层链
     * @return 单次化能力
     */
    public static Disposer once(Disposer chain) {
        Objects.requireNonNull(chain, "chain");
        AtomicReference<CompletableFuture<Void>> started = new AtomicReference<>();
        return () -> {
            CompletableFuture<Void> existing = started.get();
            if (existing != null) {
                return existing;
            }
            CompletableFuture<Void> created = new CompletableFuture<>();
            if (started.compareAndSet(null, created)) {
                chain.dispose().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        created.completeExceptionally(failure);
                    } else {
                        created.complete(null);
                    }
                });
                return created;
            }
            return started.get();
        };
    }
}
