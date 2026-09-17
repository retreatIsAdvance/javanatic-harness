/**
 * harness-sandbox-local — 本机沙箱 Provider（平台链：darwin=seatbelt、
 * linux=bwrap（需主机装 bubblewrap；非特权 userns 被禁的主机 fail-closed）、
 * win32=空链——windows-acl 入 0.2.0）。fail-closed：受限 confine 无可用
 * 后端即拒，静默透传被禁止。
 */
module io.javanatic.harness.sandbox.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.sandbox.sandbox;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.sandbox.local.SandboxLocalPlugin;

    exports io.javanatic.harness.sandbox.local;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.sandbox.local;
}
