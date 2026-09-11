/**
 * harness-sandbox-sandbox — 同机进程约束 Definition（05 §6）：文件效果模式
 * 词表、逐调用 SandboxPolicy、confine 包装（fail-closed）、WritableRoots
 * 单一来源（Seatbelt 授予与进程内 fs 围栏共用，永不漂移）。
 */
module io.javanatic.harness.sandbox.sandbox {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.sandbox.sandbox;
}
