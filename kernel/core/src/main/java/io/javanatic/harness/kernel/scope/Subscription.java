package io.javanatic.harness.kernel.scope;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次注册的回收句柄。幂等：多次 close 只有第一次生效。
 * close 先从 scope effect 栈摘除，再执行回收器——顺序保证即使回收器抛异常，
 * 该条目也不会被 scope close 二次回收。
 */
public final class Subscription implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AutoCloseable disposer;
    private final Runnable removeFromStack;

    Subscription(AutoCloseable disposer, Runnable removeFromStack) {
        this.disposer = disposer;
        this.removeFromStack = removeFromStack;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            removeFromStack.run();
            try {
                disposer.close();
            } catch (Exception e) {
                throw new RuntimeException("Disposer failed", e);
            }
        }
    }

    /** 是否已回收。 */
    public boolean isClosed() {
        return closed.get();
    }
}
