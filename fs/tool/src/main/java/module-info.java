/**
 * harness-fs-tool — fs Consumer: registers filesystem tools behind the R2
 * executor pipeline (plugin id "fs-tool"). Tools never approve; approval is
 * the executor's fixed stage (05 §4).
 */
module io.javanatic.harness.fs.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.fs.fs;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.core.session;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.fs.tool.FsToolPlugin;

    exports io.javanatic.harness.fs.tool;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.fs.tool;
}
