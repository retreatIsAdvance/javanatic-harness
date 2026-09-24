package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.tools.ApprovalDeniedException;
import io.javanatic.harness.tools.ApprovalService;

import java.time.Duration;
import java.util.Objects;

/**
 * 人工审批 Provider（id "approval-ask"，HUMAN_GATE）。组合需先于 "tools" 装载。
 * 交互通道注入：默认 headless stdin；空闲上限读行配置 {@code idleTimeoutSeconds}
 * （秒；0 = 不设限），未配置时按终端形态（{@link ApprovalPrompt#effectiveIdleTimeout}）。
 * 非交互环境（EOF / 空闲超时）为拒绝语义。
 */
public final class ApprovalAskPlugin implements Plugin {

    private final ApprovalPrompt explicitPrompt;

    /** 数据组合路径：headless stdin 人闸（空闲上限读行配置 / 终端形态缺省）。 */
    public ApprovalAskPlugin() {
        this.explicitPrompt = null;
    }

    /** @param prompt 交互通道（显式注入：测试 / 未来 UI 接管） */
    public ApprovalAskPlugin(ApprovalPrompt prompt) {
        this.explicitPrompt = Objects.requireNonNull(prompt, "prompt");
    }

    @Override
    public String id() {
        return "approval-ask";
    }

    @Override
    public void apply(Scope scope) {
        ApprovalPrompt prompt = explicitPrompt != null
            ? explicitPrompt
            : ApprovalPrompt.stdin(idleTimeout(scope));
        scope.provide(ApprovalService.KEY, new AskApproval(prompt));
    }

    /** 有效空闲上限:行配置显式秒数优先(<0 = 未配置),缺省按终端形态(单源在 ApprovalPrompt)。 */
    private Duration idleTimeout(Scope scope) {
        long configured = scope.resolve(ConfigService.KEY)
            .map(config -> ConfigValues.longValue(config.configFor(id()), id(), "idleTimeoutSeconds", -1L))
            .orElse(-1L);
        return ApprovalPrompt.effectiveIdleTimeout(configured, System.console() != null);
    }

    private record AskApproval(ApprovalPrompt prompt) implements ApprovalService {

        @Override
        public Mode mode() {
            return Mode.HUMAN_GATE;
        }

        @Override
        public void require(ApprovalRequest request, AbortSignal signal) {
            if (!prompt.ask(request, signal)) {
                throw new ApprovalDeniedException("denied by human gate: " + request.toolName());
            }
        }
    }
}
