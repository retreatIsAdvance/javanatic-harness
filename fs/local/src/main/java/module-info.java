/**
 * harness-fs-local — local filesystem provider (plugin id "fs-local").
 */
module io.javanatic.harness.fs.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.fs.fs;

    exports io.javanatic.harness.fs.local;
}
