package io.javanatic.harness.session;

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
        scope.provide(SessionStore.KEY, new SessionStore());
    }
}
