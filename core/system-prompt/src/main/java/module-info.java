/**
 * harness-core-system-prompt — 系统提示词组装注册表。assemble 只读 session 日志
 * （R1：同日志必同提示词），contributor 注册随 scope 回收（R3）。
 */
module io.javanatic.harness.core.system.prompt {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.session;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.systemprompt.SystemPromptPlugin;

    exports io.javanatic.harness.systemprompt;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.systemprompt;
}
