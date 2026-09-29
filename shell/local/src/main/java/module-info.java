/**
 * harness-shell-local — local shell provider (plugin id "shell-local"): argv is
 * dispatched per platform (POSIX = bash -c, Windows = pwsh -EncodedCommand; missing
 * pwsh fails loud); bounded direct run with process-tree kill, output cap and
 * timeout; restricted policies wrap argv via SandboxProvider.confine
 * (fail-closed without a provider).
 */
module io.javanatic.harness.shell.local {
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.llm.llm;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.shell.local.LocalShellPlugin;

    exports io.javanatic.harness.shell.local;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.shell.local;
}
