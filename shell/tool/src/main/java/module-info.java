/**
 * harness-shell-tool — shell Consumer: registers the shell tool behind the R2
 * executor pipeline (plugin id "shell-tool"). Tools never approve; approval is
 * the executor's fixed stage (05 §4).
 */
module io.javanatic.harness.shell.tool {
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.shell.tool.ShellToolPlugin;

    exports io.javanatic.harness.shell.tool;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.shell.tool;
}
