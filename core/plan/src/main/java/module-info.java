/**
 * harness-core-plan — 计划模式（dsh plan-mode 的 JH 形状）：plan/mode 扩展事件
 * 纯 fold（末值胜）+ plan:policy 动态提示段（order 50）+ exit_plan_mode 工具
 * （目录稳定：激活与否恒注册；过审后直接落账翻转）。审批骑 executor 固定 stage。
 */
module io.javanatic.harness.core.plan {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.session.persistence;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.plan.PlanModePlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec with io.javanatic.harness.plan.PlanModeCodec;

    exports io.javanatic.harness.plan;
}
