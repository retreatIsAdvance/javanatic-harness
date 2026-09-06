package io.javanatic.harness.shell.tool;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.shell.bash.local.BashLocalOptions;
import io.javanatic.harness.shell.bash.local.BashLocalPlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** bash 工具经完整 R2 pipeline(装载→注册→审批→执行→落账)。 */
class ShellToolEndToEndTest {

    @TempDir
    Path workspace;

    @Test
    void bashToolExecutesThroughPipelineAndLeavesAuditTrail() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new BashLocalPlugin(new BashLocalOptions(64 * 1024)),
                new ShellToolPlugin(workspace, Duration.ofSeconds(10))));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            ToolRegistry registry = rt.root().require(ToolRegistry.KEY);
            assertThat(registry.schemas().stream().map(schema -> schema.name()).toList())
                .contains("bash");

            Session session = Session.create(Session.newId("st"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(
                List.of(new ToolUseBlock(CallId.of("c1"), "bash",
                    "{\"command\":\"echo tool-ran > proof.txt && cat proof.txt\"}")),
                session, 0, 0, AbortSignal.never());

            assertThat(results).hasSize(1);
            ToolResultEvent result = results.getFirst().event();
            assertThat(result.block().isError()).isFalse();
            assertThat(result.block().content()).contains("tool-ran");
            assertThat(Files.readString(workspace.resolve("proof.txt"))).isEqualTo("tool-ran\n");
            assertThat(session.events().stream().map(e -> e.event().type()).toList())
                .containsExactly("tool/call", "tool/result");
        }
    }

    @Test
    void nonZeroExitBecomesErrorResult() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new BashLocalPlugin(new BashLocalOptions(64 * 1024)),
                new ShellToolPlugin(workspace, Duration.ofSeconds(10))));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("st2"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(
                List.of(new ToolUseBlock(CallId.of("c1"), "bash",
                    "{\"command\":\"echo bad >&2; exit 7\"}")),
                session, 0, 0, AbortSignal.never());

            assertThat(results.getFirst().event().block().isError()).isTrue();
            assertThat(results.getFirst().event().block().content())
                .contains("exit: 7").contains("bad");
        }
    }
}
