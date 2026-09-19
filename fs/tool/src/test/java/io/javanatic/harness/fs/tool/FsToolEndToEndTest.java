package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.plan.PlanModeEvent;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.plan.PlanModePlugin;
import io.javanatic.harness.sandbox.local.SandboxLocalPlugin;
import io.javanatic.harness.sandbox.policy.SandboxPolicyPlugin;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;

import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."), new SandboxLocalPlugin(),
                new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir)),
                new FsLocalPlugin(dir), new FsToolPlugin()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            ToolRegistry registry = rt.root().require(ToolRegistry.KEY);
            assertThat(registry.schemas(rt.root())).extracting(s -> s.name())
                .containsExactly("exit_plan_mode", "fs_delete", "fs_edit", "fs_list", "fs_read", "fs_write");

            Path file = dir.resolve("note.txt");
            Session session = Session.create(Session.newId("e2e"), null, null);
            executor.execute(List.of(
                new ToolUseBlock(CallId.of("w1"), "fs_write",
                    "{\"path\":\"" + file + "\",\"content\":\"hello fs\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());
            List<LoggedEvent<ToolResultEvent>> reads = executor.execute(List.of(
                new ToolUseBlock(CallId.of("r1"), "fs_read", "{\"path\":\"" + file + "\"}")),
                session, 0, 1, rt.root(), AbortSignal.never());

            assertThat(reads.getFirst().event().block().content()).isEqualTo("hello fs");
            assertThat(session.events().stream().map(LoggedEvent::type)).containsExactly(
                "tool/call", "tool/result", "tool/call", "tool/result");
            // 投影：两条工具结果都以 UserMessage(source=Tool) 进入模型历史
            assertThat(session.deriveMessages()).hasSize(2);
        }
    }


    /** 进程内围栏：plan 模式（fold 压 READ_ONLY）下变异工具拒、只读工具放行。 */
    @Test
    void planModeFencesMutatingFsTools() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."), new SandboxLocalPlugin(),
                new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir)),
                new FsLocalPlugin(dir), new FsToolPlugin()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            Files.writeString(dir.resolve("note.txt"), "readable");
            Session session = Session.create(Session.newId("fenced"), null, null);
            session.append(new PlanModeEvent(1, true));
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("w1"), "fs_write",
                    "{\"path\":\"fenced.txt\",\"content\":\"x\"}"),
                new ToolUseBlock(CallId.of("r1"), "fs_read", "{\"path\":\"note.txt\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());

            assertThat(results.get(0).event().block().isError()).isTrue();
            assertThat(results.get(0).event().block().content())
                .contains("denied by sandbox").contains("read-only");
            assertThat(results.get(1).event().block().isError()).isFalse();
            // 同批并行：交错序任意（配对靠 callId，相邻性非契约——it12.6 结论；
            // 固定「call,call,result,result」形状在 CI 上偶发红）
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsExactlyInAnyOrder("plan/mode", "tool/call", "tool/call",
                    "tool/result", "tool/result");
        }
    }
}
