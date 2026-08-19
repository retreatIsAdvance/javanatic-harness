package io.javanatic.harness.kernel.events;

import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.Disposable;

/**
 * ScopedEvents 实现：订阅经 {@code owner.effect} 挂 effect 栈，随 owner LIFO 回收。
 * Effect 的注册/回收分离在此兑现——subscribe* 返回的注销器就是 effect 的回收器。
 *
 * 绑定（bind）与归属（owner）分离：bind 决定事件过滤（收到 bind 及其后代
 * 派发的事件），owner 决定注销登记。普通 scope 两者同一；挂载视图
 * （forMount）bind=共享 root、owner=插件私有栈——插件收到整个挂载子树的
 * 事件，插件 close 即退订。
 */
final class ScopedEventsImpl implements ScopedEvents {

    private final Events events;
    private final Scope bind;
    private final Scope owner;

    ScopedEventsImpl(Events events, Scope scope) {
        this(events, scope, scope);
    }

    ScopedEventsImpl(Events events, Scope bind, Scope owner) {
        this.events = events;
        this.bind = bind;
        this.owner = owner;
    }

    @Override
    public <T> Disposable on(EventKey<T> key, EventListener<? super T> listener) {
        return owner.effect(() -> events.subscribeNotify(key, listener, bind));
    }

    @Override
    public <T> Disposable onGlobal(EventKey<T> key, EventListener<? super T> listener) {
        return owner.effect(() -> events.subscribeNotify(key, listener, null));
    }

    @Override
    public <T> Disposable onWaterfall(EventKey<T> key, WaterfallListener<? super T> listener) {
        return owner.effect(() -> events.subscribeWaterfall(key, listener, bind));
    }
}
