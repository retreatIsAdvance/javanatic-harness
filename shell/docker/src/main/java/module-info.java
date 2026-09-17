/**
 * harness-shell-docker — 环境级隔离 Provider（id "shell-docker"）：命令经
 * docker exec 在容器内执行。容器按 (workspace, mode) 键惰性创建——挂载面即
 * 可写面（根文件系统只读，可写=显式挂载），沙箱三档词表同义且更强；生命周期
 * 挂 scope close（R3）；docker CLI 经 ProcessBuilder（零新依赖，CLI 即契约）。
 */
module io.javanatic.harness.shell.docker {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.shell.shell;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.shell.docker.DockerShellPlugin;

    exports io.javanatic.harness.shell.docker;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.shell.docker;
}
