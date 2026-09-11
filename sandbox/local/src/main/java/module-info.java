/**
 * harness-sandbox-local — 本机沙箱 Provider（平台链：darwin=seatbelt；linux/
 * windows 后端设计先行，实现挂 it13 CI）。fail-closed：受限 confine 无可用
 * 后端即拒，静默透传被禁止。
 */
module io.javanatic.harness.sandbox.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.sandbox.sandbox;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.sandbox.local.SandboxLocalPlugin;

    exports io.javanatic.harness.sandbox.local;
}
