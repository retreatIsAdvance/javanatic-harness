package io.javanatic.harness.interaction.ask;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.util.Map;
import java.util.Set;

/**
 * ask_user（id "ask-user"，requires "tools"）：模型问一句、人答一句的澄清通道。
 * 两个声明面共同定义形状——免审批（{@link ToolDefinition#ofExempt}：提问不是
 * 副作用，拒批不该堵住澄清）与停轮结果（{@code concluding}：轮在该 step 后自然
 * 收口，答复 = 下一轮 user message）。非交互场景因此结构上不可能挂等：出口契约
 * 由 CLI 面给（stdout = 提问文本 + 退出码，见 12 §6）。
 */
public final class AskUserPlugin implements Plugin {

    private static final String TOOL = "ask_user";
    private static final ValueSchema.Str QUESTION = new ValueSchema.Str(
        "The question for the human, asked plainly and completely. One question per call — "
            + "no option lists, no multi-part checklists.");

    @Override
    public String id() {
        return "ask-user";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.onClose(registry.register(scope, ToolDefinition.ofExempt(TOOL, describe(),
            new ValueSchema.Object("参数", Map.of("question", QUESTION)),
            (args, context) -> ToolExecutionResult.concluding(question(args.readString("question"))))));
    }

    /** 值约束（schema 表达不了的部分）：trim 非空——空问题落 error result（错误即数据，不停轮）。 */
    private static String question(String raw) {
        String question = raw.trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("invalid question: must be a non-empty string");
        }
        return question;
    }

    /** 模型可见说明：何时该问、问法边界、停轮与答复通道。 */
    private static String describe() {
        return "Ask the human one clarifying question and end your turn. Use it when the task is "
            + "genuinely ambiguous, when it hinges on a decision only the user can make, or when "
            + "required details (paths, names, credentials, intent) are missing — not for "
            + "confirmation of work you could verify yourself. The question ends the turn; the "
            + "answer arrives as the user's next message, so do not guess or keep working in the "
            + "meantime.";
    }
}
