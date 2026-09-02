package io.javanatic.harness.llm;

/**
 * 取消信号：consumer 在阻塞读/遍历的间隙调用 {@link #checkAbort()}，
 * 已取消则抛 {@link AbortedException}。适配器在自有循环里轮询同一信号。
 */
public interface AbortSignal {

    /**
     * @throws AbortedException 已取消时
     */
    void checkAbort();

    /** 永不取消的信号（测试与回放默认）。 */
    static AbortSignal never() {
        return () -> { };
    }
}
