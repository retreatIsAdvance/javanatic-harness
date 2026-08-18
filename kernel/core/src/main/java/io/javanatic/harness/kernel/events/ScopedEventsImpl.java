package io.javanatic.harness.kernel.events;

import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.Disposable;

/**
 * ScopedEvents 实现：订阅经 {@code scope.effect} 挂 effect 栈，随 scope LIFO 回收。
 * Effect 的注册/回收分离在此兑现——subscribe* 返回的注销器就是 effect 的回收器。
 */
final class ScopedEventsImpl implements ScopedEvents {

    private final Events events;
    private final Scope scope;

    ScopedEventsImpl(Events events, Scope scope) {
        this.events = events;
        this.scope = scope;
    }

    @Override
    public <T> Disposable on(EventKey<T> key, EventListener<? super T> listener) {
        return scope.effect(() -> events.subscribeNotify(key, listener, scope));
    }

    @Override
    public <T> Disposable onGlobal(EventKey<T> key, EventListener<? super T> listener) {
        return scope.effect(() -> events.subscribeNotify(key, listener, null));
    }

    @Override
    public <T> Disposable onWaterfall(EventKey<T> key, WaterfallListener<? super T> listener) {
        return scope.effect(() -> events.subscribeWaterfall(key, listener, scope));
    }
}
