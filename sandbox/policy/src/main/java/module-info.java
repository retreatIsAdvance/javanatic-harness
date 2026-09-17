/**
 * harness-sandbox-policy — 逐调用沙箱策略解析：部署默认档（组合配置）+
 * 计划模式耦合（plan/mode fold 激活且默认档受限时压 READ_ONLY——
 * plan:policy 的「强制归沙箱层」承诺由此为真；透传档是部署显式弃权不覆盖）。
 */
module io.javanatic.harness.sandbox.policy {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.plan;
    requires io.javanatic.harness.sandbox.sandbox;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.sandbox.policy.SandboxPolicyPlugin;

    exports io.javanatic.harness.sandbox.policy;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.sandbox.policy;
}
