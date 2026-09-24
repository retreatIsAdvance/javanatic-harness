/**
 * harness-interaction-approval — 审批交互 Provider：approval-ask（headless stdin
 * 默认,回调可注入供 UI 接管——缺省即拒绝；空闲上限 it22:行配置 idleTimeoutSeconds，
 * 缺省非交互 300s / 交互不设限,超时按拒绝）与 approval-deny（全拒）。AUTO 留在
 * core/tools（executor 强制依赖的锚）;mode 自述供 --verify/policy 档位校验。
 */
module io.javanatic.harness.interaction.approval {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config; // 人闸等待界读行配置(idleTimeoutSeconds)
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.session; // 测试直用 Session 词表
    requires io.javanatic.harness.llm.llm;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.interaction.approval.ApprovalAskPlugin, io.javanatic.harness.interaction.approval.ApprovalDenyPlugin;

    exports io.javanatic.harness.interaction.approval;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.interaction.approval;
}
