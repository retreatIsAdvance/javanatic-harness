package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ApprovalDeniedException;
import io.javanatic.harness.tools.ApprovalService;

import java.util.Objects;

/**
 * 人工审批 Provider（id "approval-ask"，HUMAN_GATE）。组合需先于 "tools" 装载。
 * 交互通道注入：默认 headless stdin；非交互环境（EOF）为拒绝语义。
 */
public final class ApprovalAskPlugin implements Plugin {

    private final ApprovalPrompt prompt;

    /** @param prompt 交互通道（默认 {@link ApprovalPrompt#stdin()}） */
    public ApprovalAskPlugin(ApprovalPrompt prompt) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    @Override
    public String id() {
        return "approval-ask";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(ApprovalService.KEY, new AskApproval(prompt));
    }

    private record AskApproval(ApprovalPrompt prompt) implements ApprovalService {

        @Override
        public Mode mode() {
            return Mode.HUMAN_GATE;
        }

        @Override
        public void require(ApprovalRequest request) {
            if (!prompt.ask(request)) {
                throw new ApprovalDeniedException("denied by human gate: " + request.toolName());
            }
        }
    }
}
