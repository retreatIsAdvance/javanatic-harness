package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/**
 * 命令面插件（id "commands"）：提供 {@link CommandRegistry}。具体命令由
 * 交互适配器注册（REPL 的 /help、/exit）；codec 经 ServiceLoader 由持久化层
 * 发现，与插件装载无行序依赖。
 */
public final class CommandsPlugin implements Plugin {

    @Override
    public String id() {
        return "commands";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(CommandRegistry.KEY, new CommandRegistry());
    }
}
