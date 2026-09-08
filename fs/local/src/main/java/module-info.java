/**
 * harness-fs-local — local filesystem provider (plugin id "fs-local").
 */
module io.javanatic.harness.fs.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.fs.fs;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.fs.local.FsLocalPlugin;

    exports io.javanatic.harness.fs.local;
}
