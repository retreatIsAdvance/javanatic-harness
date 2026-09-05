package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/** 提供 SystemPromptService 默认实现（id "system-prompt"）。贡献段由各插件注册。 */
public final class SystemPromptPlugin implements Plugin {

    @Override
    public String id() {
        return "system-prompt";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(SystemPromptService.KEY, new SystemPromptImpl());
    }
}
