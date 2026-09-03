/**
 * harness-fs-tool — fs Consumer: registers filesystem tools behind the R2
 * executor pipeline (plugin id "fs-tool"). Tools never approve; approval is
 * the executor's fixed stage (05 §4).
 */
module io.javanatic.harness.fs.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.fs.fs;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.fs.tool;
}
