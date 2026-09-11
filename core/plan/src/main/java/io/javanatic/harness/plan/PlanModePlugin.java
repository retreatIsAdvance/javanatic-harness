package io.javanatic.harness.plan;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.util.Map;
import java.util.Set;

/**
 * 计划模式插件（id "plan"，requires "tools" + "system-prompt"，dsh plan-mode 形状）：
 * 生效态纯 fold（resume/fork 免镜像恢复）、plan:policy 动态提示段 order 50、
 * exit_plan_mode 恒注册（目录稳定）且过审后直接落账 plan/mode(false)——
 * 工具批 join 后的下一次 assemble 读到新 fold，「下一步生效」字面为真。
 *
 * <p>it11 审阅通道 = executor 固定审批 stage：ask 模式下人工拒绝即「继续规划」
 * 的反馈；选项式审阅与反馈回传挂账 ask-user seam。plan:policy 是提示词级指导，
 * 只读强制挂账 sandbox 迭代。同批并行 exit 重复落账 false 对 fold 幂等。
 */
public final class PlanModePlugin implements Plugin {

    /** exit 工具名（目录稳定：激活与否恒注册——进出计划模式不改请求工具目录）。 */
    public static final String EXIT_PLAN_MODE = "exit_plan_mode";

    /** plan:policy 段排序键（dsh plan-mode 对齐）。 */
    static final int SECTION_ORDER = 50;

    private static final String EXIT_DESCRIPTION =
        "Use only in plan mode. Present your plan for review and, on approval, leave plan mode. "
        + "Send the COMPLETE plan as markdown, starting with a # heading that names it. "
        + "On approval, plan mode exits at the next step boundary — carry out the plan from "
        + "your next step. A denial means the reviewer kept you in plan mode: revise the plan "
        + "and present it again.";

    private final String explicitSection;

    /** 数据组合路径：section 指导文从行配置解析（必配，缺失 fail loud）。 */
    public PlanModePlugin() {
        this.explicitSection = null;
    }

    /** @param section 规划期指导文（程序化组合的显式选择） */
    public PlanModePlugin(String section) {
        this.explicitSection = section;
    }

    @Override
    public String id() {
        return "plan";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "system-prompt");
    }

    @Override
    public void apply(Scope scope) {
        String section = explicitSection != null ? explicitSection
            : ConfigValues.requireString(
                scope.require(ConfigService.KEY).configFor(id()), id(), "section");
        PlanModeService service = new PlanModeService(section);
        scope.provide(PlanModeService.KEY, service);
        SystemPromptService prompts = scope.require(SystemPromptService.KEY);
        scope.onClose(prompts.register(new PromptSection.Dynamic(SECTION_ORDER, service::sectionFor)));
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.onClose(registry.register(scope, exitTool(service)));
    }

    private static ToolDefinition exitTool(PlanModeService service) {
        return ToolDefinition.of(EXIT_PLAN_MODE, EXIT_DESCRIPTION,
            new ValueSchema.Object("参数", Map.of(
                "plan", new ValueSchema.Str("The complete plan, as markdown, starting with a # heading that names it."))),
            (args, context) -> {
                Session session = context.session();
                if (!service.active(session)) {
                    return ToolExecutionResult.error(EXIT_PLAN_MODE + " is only available in plan mode");
                }
                String plan = args.readString("plan").trim();
                String firstLine = plan.split("\n", 2)[0];
                if (!firstLine.matches("#\\s+\\S.*")) {
                    return ToolExecutionResult.error(EXIT_PLAN_MODE
                        + " requires a non-empty markdown plan starting with a # heading");
                }
                // 已过审批 stage——直接落账(todo 同款);工具批 join 后的 assemble
                // 读到 inactive fold,plan:policy 自下一步起消失
                context.session().append(new PlanModeEvent(System.currentTimeMillis(), false));
                return ToolExecutionResult.success("Plan approved — plan mode exits from your "
                    + "next step; carry out the plan starting there.");
            });
    }
}
