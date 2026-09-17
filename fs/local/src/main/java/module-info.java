/**
 * harness-fs-local — local filesystem provider (plugin id "fs-local").
 */
module io.javanatic.harness.fs.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.fs.fs;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.fs.local.FsLocalPlugin;

    exports io.javanatic.harness.fs.local;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.fs.local;
}
