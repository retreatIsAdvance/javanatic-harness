/**
 * harness-shell-bash-local — local bash provider (plugin id "shell-bash-local"):
 * bounded direct run with process-tree kill, output cap and timeout; restricted
 * policies wrap argv via SandboxProvider.confine (fail-closed without a provider).
 */
module io.javanatic.harness.shell.bash.local {
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.llm.llm;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.shell.bash.local.BashLocalPlugin;

    exports io.javanatic.harness.shell.bash.local;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.shell.bash.local;
}
