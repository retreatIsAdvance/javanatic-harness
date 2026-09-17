/**
 * harness-interaction-commands — slash 命令注册表（Definition，dsh commands 形状）：
 * 注册即 effect + command/run|done log-only 事件对；具体命令由交互适配器注册
 * （design: docs/design/05-capability-seam.md §9）。
 */
module io.javanatic.harness.interaction.commands {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.interaction.commands.CommandsPlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec with
        io.javanatic.harness.interaction.commands.CommandRunCodec,
        io.javanatic.harness.interaction.commands.CommandDoneCodec;

    exports io.javanatic.harness.interaction.commands;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.interaction.commands;
}
