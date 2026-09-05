package io.javanatic.harness.agent;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/** 提供 AgentRegistry 服务（id "agents"）。loop 插件 requires 它并注册工厂。 */
public final class AgentPlugin implements Plugin {

    @Override
    public String id() {
        return "agents";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(AgentRegistry.KEY, new AgentRegistry());
    }
}
