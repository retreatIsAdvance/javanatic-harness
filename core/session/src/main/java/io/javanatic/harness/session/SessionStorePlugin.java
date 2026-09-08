package io.javanatic.harness.session;

import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/** 注册 SessionStore 服务（id "session-store"）。 */
public final class SessionStorePlugin implements Plugin {

    @Override
    public String id() {
        return "session-store";
    }

    @Override
    public void apply(Scope scope) {
        // boot 装配提供清单;直装组合没有(组合未知,不 fail——示例/测试合法形态)
        var manifest = scope.resolve(CompositionManifest.KEY);
        scope.provide(SessionStore.KEY, new SessionStore(manifest.orElse(null)));
    }
}
