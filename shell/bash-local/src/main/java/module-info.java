/**
 * harness-shell-bash-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
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
