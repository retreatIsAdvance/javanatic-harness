/**
 * harness-sandbox-local — 本机沙箱 Provider（平台链：darwin=seatbelt、
 * linux=bwrap 或 landlock 兜底（需主机装 bubblewrap；非特权 userns 被禁的
 * 主机落 landlock 助手）、win32=windows-acl（低完整性令牌 + 逐对象打标，
 * 完备度 PARTIAL）。fail-closed：受限 confine 无可用后端即拒，静默透传被禁止。
 */
module io.javanatic.harness.sandbox.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.sandbox.sandbox;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.sandbox.local.SandboxLocalPlugin;

    exports io.javanatic.harness.sandbox.local;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.sandbox.local;
}
