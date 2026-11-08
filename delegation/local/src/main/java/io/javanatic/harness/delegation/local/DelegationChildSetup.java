package io.javanatic.harness.delegation.local;

import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 子代继承快照的装配期执法点（停 2 设计 §3 + 停 3 模型在环裁决）：唯一允许触碰
 * 子 scope 的地方（CreateAgentOptions.setup 回调）；LocalDelegationService 永不持有
 * 子 scope 引用——ArchUnit 双路背书之一。
 * 继承面 v1：工具面 = 全量继承 **但影子封禁 delegate 四工具**（同名最近层覆盖——
 * 子代不得再委派，maxDepth=1 的结构性执法）；沙箱/审批档恒等继承（恒等也是显式
 * 决定）；预算在请求。ask_user 随全量继承在场（问态路径的前提；停 3 探针实证）。
 */
final class DelegationChildSetup implements Consumer<Scope> {

    private static final String BANNED = "递归委派已禁（maxDepth=1）：子代须独立完成任务，不得再派子代。";

    @Override
    public void accept(Scope childScope) {
        ToolRegistry registry = childScope.require(ToolRegistry.KEY);
        for (String name : new String[] {"delegate", "delegate_list", "delegate_answer", "delegate_interrupt"}) {
            childScope.onClose(registry.register(childScope, ToolDefinition.of(name, BANNED,
                    new ValueSchema.Object("参数", Map.of()),
                    (args, context) -> ToolExecutionResult.error(BANNED))));
        }
    }
}
