/**
 * harness-interaction-approval — 审批交互 Provider：approval-ask（headless stdin
 * 默认,回调可注入供 UI 接管——缺省即拒绝）与 approval-deny（全拒）。AUTO 留在
 * core/tools（executor 强制依赖的锚）;mode 自述供 --verify/policy 档位校验。
 */
module io.javanatic.harness.interaction.approval {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.session; // 测试直用 Session 词表
    requires io.javanatic.harness.llm.llm;

    exports io.javanatic.harness.interaction.approval;
}
