package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.tools.ApprovalService;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 三模式语义:mode 自述、ask 放行/拒绝、deny 全拒、缺省拒绝。 */
class ApprovalModesTest {

    private static ToolDefinition echoTool() {
        return ToolDefinition.of("echo", "回显",
            new ValueSchema.Object("参数", Map.of()),
            (args, ctx) -> ToolExecutionResult.success("ok"));
    }

    @Test
    void askApprovesThroughInjectedPromptAndLogsDenials() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAskPlugin(request -> {
                    asked.incrementAndGet();
                    return true;
                }),
                new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            assertThat(rt.root().require(ApprovalService.KEY).mode())
                .isEqualTo(ApprovalService.Mode.HUMAN_GATE);
            assertThat(execute(executor, rt).block().isError()).isFalse();
            assertThat(asked.get()).isEqualTo(1);
        }
    }

    @Test
    void askDenialBecomesErrorResultNotCrash() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAskPlugin(request -> false), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            var result = execute(executor, rt);
            assertThat(result.block().isError()).isTrue();
            assertThat(result.block().content()).contains("denied by human gate");
        }
    }

    @Test
    void denyAllRejectsEverythingWithModeSelfReport() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalDenyPlugin(), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(echoTool());
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            assertThat(rt.root().require(ApprovalService.KEY).mode())
                .isEqualTo(ApprovalService.Mode.DENY_ALL);
            var result = execute(executor, rt);
            assertThat(result.block().isError()).isTrue();
            assertThat(result.block().content()).contains("denied by policy");
        }
    }

    @Test
    void stdinPromptRejectsOnEofAndNonYes() throws Exception {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(ApprovalPrompt.stdin().ask(new ApprovalService.ApprovalRequest(
                "bash", "rm -rf /", "{}"))).isFalse();
            System.setIn(new ByteArrayInputStream(new byte[0]));
            assertThat(ApprovalPrompt.stdin().ask(new ApprovalService.ApprovalRequest(
                "bash", "rm -rf /", "{}"))).isFalse();
        } finally {
            System.setIn(original);
        }
    }

    private ToolResultEvent execute(ToolExecutor executor, Runtime rt) {
        return executor.execute(
            List.of(new ToolUseBlock(CallId.of("c1"), "echo", "{}")),
            Session.create(Session.newId("a"), null, null),
            0, 0, AbortSignal.never()).getFirst().event();
    }
}
