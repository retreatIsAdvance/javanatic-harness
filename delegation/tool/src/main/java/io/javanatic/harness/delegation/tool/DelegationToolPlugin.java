package io.javanatic.harness.delegation.tool;

import io.javanatic.harness.delegation.Delegation;
import io.javanatic.harness.delegation.DelegationException;
import io.javanatic.harness.delegation.DelegationRequest;
import io.javanatic.harness.delegation.DelegationService;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolArgs;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 委派消费面（插件 id "delegation-tool"，it31 停 3）：{@link DelegationService} 在场才注册
 * 四工具（双态，沿 jobs/lsp 先例；缺省组合零工具零委派零噪声）。恒需审批（派子代 =
 * 花预算的副作用，读面同批治理，B14 同口径无豁免出口）。工作目录显式绝对（shell/jobs
 * 同立场）；预算显式正数（继承面收窄值）。结果文本由 {@link DelegationToolOutput}
 * 逐字渲染（问败分界 + 树链接可见）。
 */
public final class DelegationToolPlugin implements Plugin {

    // 包可见供措辞钉死测试（it32：诚实交易口径——实测判据入说明书，禁绝对化承诺）
    static final String DELEGATE_DESCRIPTION =
        "把一件完整子任务派给子代并立即返回（异步）：子代在独立 durable 会话上执行，"
        + "本会话只见摘要与终局。何时用：中间产物大而结论小（多源检索汇总、批量排查、"
        + "长文深读提炼）——代价是树总 token 约为数倍（每代各背系统提示基线），换来的"
        + "是父上下文不受中间产物污染；先问自己在保护什么，答「父上下文」且任务够肥"
        + "才委派。何时不：微任务或强依赖链（下一步要吃上一步的中间态）——直做更省。"
        + "等待：别频繁轮询 delegate_list（每次都花父上下文），隔开再查或先做别的。"
        + "子代遇前提不明会以 needs_parent_input 提问（问题原文随状态可见），"
        + "转给用户后用 delegate_answer 答复。";

    static final String LIST_DESCRIPTION =
        "列出本会话全部委派（含终局与失联）及各子任务摘要、状态、预算与子会话链接。"
        + "见 needs_parent_input 即停轮询：问题原文在状态里，转给用户，"
        + "答复经 delegate_answer 发回；succeeded 的结果摘要可直接引用。";

    private static final String ANSWER_DESCRIPTION =
        "答复处于 needs_parent_input 的子代：答复文本作为子代下一轮 user message 继续。"
        + "仅暂停态可答复。";

    private static final String INTERRUPT_DESCRIPTION =
        "取消运行中或等待输入的子代（TERM 语义经会话轮取消收敛）；终局委派返回未发起。";

    @Override
    public String id() {
        return "delegation-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.resolve(DelegationService.KEY).ifPresent(service -> {
            scope.onClose(registry.register(scope, delegateTool(service)));
            scope.onClose(registry.register(scope, listTool(service)));
            scope.onClose(registry.register(scope, answerTool(service)));
            scope.onClose(registry.register(scope, interruptTool(service)));
        });
    }

    private static ToolDefinition delegateTool(DelegationService service) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "task", new ValueSchema.Str("交给子代的完整任务文本（子代唯一任务来源）"),
                "cwd", new ValueSchema.Str("子代工作目录绝对路径（执行边界无默认）"),
                "budgetTokens", new ValueSchema.Num("子代 token 预算（正数，只能收窄）")));
        return ToolDefinition.of("delegate", DELEGATE_DESCRIPTION, schema, (args, context) -> {
            try {
                DelegationRequest request = new DelegationRequest(args.readString("task"),
                        Path.of(args.readString("cwd")), args.readOptionalLong("budgetTokens").orElseThrow());
                return ToolExecutionResult.success(DelegationToolOutput.started(service.start(request)));
            } catch (DelegationException e) {
                return ToolExecutionResult.error(DelegationToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    private static ToolDefinition listTool(DelegationService service) {
        return ToolDefinition.of("delegate_list", LIST_DESCRIPTION,
                new ValueSchema.Object("参数", Map.of()), (args, context) ->
                        ToolExecutionResult.success(DelegationToolOutput.list(service.list())));
    }

    private static ToolDefinition answerTool(DelegationService service) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "delegationId", new ValueSchema.Str("委派 id"),
                "answer", new ValueSchema.Str("父的答复文本（作为子代下一轮 user message）")));
        return ToolDefinition.of("delegate_answer", ANSWER_DESCRIPTION, schema, (args, context) -> {
            try {
                DelegationSnapshot resumed = service.resume(id(args), args.readString("answer"));
                return ToolExecutionResult.success("已答复，子代继续：" + DelegationToolOutput.line(resumed));
            } catch (DelegationException | IllegalStateException e) {
                return ToolExecutionResult.error(DelegationToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    private static ToolDefinition interruptTool(DelegationService service) {
        ValueSchema schema = new ValueSchema.Object("参数", Map.of(
                "delegationId", new ValueSchema.Str("委派 id")));
        return ToolDefinition.of("delegate_interrupt", INTERRUPT_DESCRIPTION, schema, (args, context) -> {
            try {
                boolean interrupted = service.cancel(id(args));
                return ToolExecutionResult.success(interrupted
                        ? "已发起取消收敛"
                        : "委派已终局，未发起取消");
            } catch (DelegationException e) {
                return ToolExecutionResult.error(DelegationToolOutput.error(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.error("参数错误（" + e.getMessage() + "）");
            }
        });
    }

    private static Id<Delegation> id(ToolArgs args) {
        return Delegation.newId(args.readString("delegationId"));
    }
}
