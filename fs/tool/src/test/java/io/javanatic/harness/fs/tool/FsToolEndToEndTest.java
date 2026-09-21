package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.kernel.config.ConfigService;
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
import java.util.Map;

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


    /** 有界化（it20）：读截断标记在文内（非错误）、列举截断尾行、编辑超限 fail loud 经 pipeline 显形。 */
    @Test
    void boundedReadsListsAndEditsSurfaceThroughTools() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "fs-local".equals(id)
                ? Map.of("root", dir.toString(), "maxReadBytes", 4L, "maxListEntries", 2L)
                : Map.of());
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."), new SandboxLocalPlugin(),
                new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir)),
                new FsLocalPlugin(), new FsToolPlugin()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            Files.writeString(dir.resolve("big.txt"), "hello world");
            Files.writeString(dir.resolve("a.txt"), "1");
            Files.writeString(dir.resolve("b.txt"), "2");
            Files.writeString(dir.resolve("c.txt"), "3");
            Session session = Session.create(Session.newId("bounded"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("r1"), "fs_read", "{\"path\":\"big.txt\"}"),
                new ToolUseBlock(CallId.of("l1"), "fs_list", "{\"path\":\"" + dir + "\"}"),
                new ToolUseBlock(CallId.of("e1"), "fs_edit",
                    "{\"path\":\"big.txt\",\"old_string\":\"hello\",\"new_string\":\"x\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());

            // fs_read：截断是内容事实，非错误；标记与实现契约同文本
            assertThat(results.get(0).event().block().isError()).isFalse();
            assertThat(results.get(0).event().block().content())
                .isEqualTo("hell" + FsService.READ_TRUNCATED_MARKER);
            // fs_list：排序后取前 2 条 + 截断尾行
            assertThat(results.get(1).event().block().isError()).isFalse();
            assertThat(results.get(1).event().block().content())
                .isEqualTo("f a.txt\nf b.txt\n… (list truncated)");
            // fs_edit：超限 fail loud → error result（消息含实际大小）
            assertThat(results.get(2).event().block().isError()).isTrue();
            assertThat(results.get(2).event().block().content())
                .contains("file too large to edit").contains("11 bytes");
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
