package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 端到端：fs 工具经完整 R2 pipeline（装载→注册→审批→执行→落账→投影）。 */
class FsToolEndToEndTest {

    @TempDir
    Path dir;

    @Test
    void fsToolsExecuteThroughPipelineAndLeaveAuditTrail() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new FsLocalPlugin(), new FsToolPlugin()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            ToolRegistry registry = rt.root().require(ToolRegistry.KEY);
            assertThat(registry.schemas()).extracting(s -> s.name())
                .containsExactly("fs_delete", "fs_edit", "fs_list", "fs_read", "fs_write");

            Path file = dir.resolve("note.txt");
            Session session = Session.create(Session.newId("e2e"), null, null);
            executor.execute(List.of(
                new ToolUseBlock(CallId.of("w1"), "fs_write",
                    "{\"path\":\"" + file + "\",\"content\":\"hello fs\"}")),
                session, 0, 0, AbortSignal.never());
            List<LoggedEvent<ToolResultEvent>> reads = executor.execute(List.of(
                new ToolUseBlock(CallId.of("r1"), "fs_read", "{\"path\":\"" + file + "\"}")),
                session, 0, 1, AbortSignal.never());

            assertThat(reads.getFirst().event().block().content()).isEqualTo("hello fs");
            assertThat(session.events().stream().map(LoggedEvent::type)).containsExactly(
                "tool/call", "tool/result", "tool/call", "tool/result");
            // 投影：两条工具结果都以 UserMessage(source=Tool) 进入模型历史
            assertThat(session.deriveMessages()).hasSize(2);
        }
    }
}
