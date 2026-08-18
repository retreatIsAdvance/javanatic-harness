package io.javanatic.harness.kernel.scope;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次注册的回收凭据：provide / effect / 事件订阅共用的撤销句柄。
 * 名字说"可释放"而非"订阅"——契约覆盖一切注册，事件订阅只是其一
 * （订阅仍是事件侧的动词，本类是注册返回的凭据）。
 * 幂等：多次 close 只有第一次生效。
 * close 先从 scope effect 栈摘除，再执行回收器——顺序保证即使回收器抛异常，
 * 该条目也不会被 scope close 二次回收。
 */
public final class Disposable implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AutoCloseable disposer;
    private final Runnable removeFromStack;

    Disposable(AutoCloseable disposer, Runnable removeFromStack) {
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
