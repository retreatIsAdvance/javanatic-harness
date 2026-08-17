package io.javanatic.harness.kernel.plugin;

import io.javanatic.harness.kernel.events.ScopedEvents;
import io.javanatic.harness.kernel.scope.Effect;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.kernel.scope.Subscription;

import java.util.Optional;

/**
 * 插件挂载视图：provide 落共享 mount root（跨插件可见），effect / 订阅 / 子 scope
 * 落插件私有 child（close 即整体回滚，R3）。解析沿私有 child 向上，二者都可见。
 * 包私有：Plugin 只见 Scope 接口，视图的存在是内核装配细节。
 */
final class PluginScope implements Scope {

    private final Scope shared;
    private final Scope own;

    PluginScope(Scope shared, Scope own) {
        this.shared = shared;
        this.own = own;
    }

    @Override
    public <T> Subscription provide(ServiceKey<T> key, T impl) {
        Subscription provided = shared.provide(key, impl);
        return own.effect(() -> provided); // 注销绑定插件私有 scope，回滚即摘除
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
    public Subscription effect(Effect effect) {
        return own.effect(effect);
    }

    @Override
    public Subscription onClose(AutoCloseable closeable) {
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
