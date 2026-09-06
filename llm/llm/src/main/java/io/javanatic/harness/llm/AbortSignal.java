package io.javanatic.harness.llm;

/**
 * 取消信号：consumer 在阻塞读/遍历的间隙调用 {@link #checkAbort()}，
 * 已取消则抛 {@link AbortedException}。适配器在自有循环里轮询同一信号。
 * 需要即时响应（如 kill 子进程）的 consumer 另挂 {@link #onCancel(Runnable)}——
 * 轮询覆盖「等待间隙」，监听覆盖「阻塞在不可中断 IO」。
 */
public interface AbortSignal {

    /**
     * @throws AbortedException 已取消时
     */
    void checkAbort();

    /**
     * 注册取消发生时同步执行的动作（默认无操作——仅轮询即可的 consumer 不实现）。
     * 语义：已取消时由实现决定（控制器的实现立即执行）；动作须幂等/无害重复。
     *
     * @param action 取消动作
     */
    default void onCancel(Runnable action) {
        // 默认无操作:该信号不支持监听(如 never())
    }

    /** 永不取消的信号（测试与回放默认）。 */
    static AbortSignal never() {
        return () -> { };
    }
}
