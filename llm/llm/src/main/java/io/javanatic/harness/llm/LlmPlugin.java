package io.javanatic.harness.llm;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/** 提供 LlmService 路由默认实现（id "llm"）。Provider 插件声明 requires "llm"。 */
public final class LlmPlugin implements Plugin {

    @Override
    public String id() {
        return "llm";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(LlmService.KEY, new RoutingLlmService());
    }
}
