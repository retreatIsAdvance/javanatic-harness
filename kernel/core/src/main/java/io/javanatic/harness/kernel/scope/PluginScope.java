package io.javanatic.harness.kernel.scope;

import io.javanatic.harness.kernel.events.ScopedEvents;

import java.util.Optional;

/**
 * 插件挂载视图（{@link Runtime#mountScope()} 创建）：provide 落共享 root
 * （跨插件可见），effect / 订阅 / 子 scope 落插件私有 child（close 即整体回滚，R3）。
 *
 * 放在 .scope 包的原因：provide 需要 {@link ScopeImpl} 的无栈注册通道
 * （registerService），把"注销服务"登记到插件私有栈——插件的 teardown effect
 * 先跑（此刻自己的服务仍可见），服务摘除恒为插件回收的最后一步；
 * 若走 root.provide，注销器会落在 root 栈的级联 entry 之上，顺序相反。
 * 包私有：Plugin 只见 Scope 接口，视图的存在是内核装配细节。
 */
final class PluginScope implements Scope {

    private final ScopeImpl shared;
    private final Scope own;

    PluginScope(ScopeImpl shared, Scope own) {
        this.shared = shared;
        this.own = own;
    }

    @Override
    public <T> Disposable provide(ServiceKey<T> key, T impl) {
        return own.effect(() -> shared.registerService(key, impl));
    }

    @Override
    public <T> Optional<T> resolve(ServiceKey<T> key) {
        return own.resolve(key);
    }

    @Override
    public <T> T require(ServiceKey<T> key) {
        return own.require(key);
    }

    @Override
    public Disposable effect(Effect effect) {
        return own.effect(effect);
    }

    @Override
    public Disposable onClose(AutoCloseable closeable) {
        return own.onClose(closeable);
    }

    @Override
    public Scope child() {
        return own.child();
    }

    @Override
    public Scope parent() {
        return own.parent();
    }

    @Override
    public ScopedEvents events() {
        return own.events();
    }

    @Override
    public void close() {
        own.close();
    }

    @Override
    public boolean isClosed() {
        return own.isClosed();
    }
}
