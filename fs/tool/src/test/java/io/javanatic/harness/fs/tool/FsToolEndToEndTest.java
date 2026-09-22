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
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolResultBlock;
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
                .containsExactly("exit_plan_mode", "fs_delete", "fs_edit", "fs_list", "fs_read",
                    "fs_search", "fs_write");

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

    // ===== 唯一匹配 + 读后修改保护（it21）=====

    /** it21 唯一匹配：多处匹配经 pipeline 拒绝（error result 含计数与行号），文件不动。 */
    @Test
    void ambiguousEditIsRejectedThroughPipeline() throws Exception {
        try (Runtime rt = boot(dir)) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Path file = dir.resolve("dup.txt");
            Files.writeString(file, "same\nsame\n");
            Session session = Session.create(Session.newId("dup"), null, null);

            ToolResultBlock result = run(executor, session, rt, new ToolUseBlock(CallId.of("e1"),
                "fs_edit", json(file, "old_string", "same", "new_string", "x")));

            assertThat(result.isError()).isTrue();
            assertThat(result.content())
                .contains("oldString is not unique").contains("2 occurrences").contains("lines 1, 2");
            assertThat(Files.readString(file)).isEqualTo("same\nsame\n");   // 拒而未写
        }
    }

    /** it21 读后修改保护：读过 → 外部改写 → 编辑被拒；重读后放行。 */
    @Test
    void externallyModifiedFileIsRejectedUntilReread() throws Exception {
        try (Runtime rt = boot(dir)) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Path file = dir.resolve("note.txt");
            Session session = Session.create(Session.newId("stale"), null, null);
            run(executor, session, rt, new ToolUseBlock(CallId.of("w1"), "fs_write",
                json(file, "content", "v1")));
            run(executor, session, rt, new ToolUseBlock(CallId.of("r1"), "fs_read",
                "{\"path\":\"" + file + "\"}"));

            Files.writeString(file, "external");   // 模型视角之外的外部修改

            ToolResultBlock rejected = run(executor, session, rt, new ToolUseBlock(CallId.of("e1"),
                "fs_edit", json(file, "old_string", "v1", "new_string", "v2")));
            assertThat(rejected.isError()).isTrue();
            assertThat(rejected.content()).contains("file changed since read");
            assertThat(Files.readString(file)).isEqualTo("external");   // 拒而未写

            run(executor, session, rt, new ToolUseBlock(CallId.of("r2"), "fs_read",
                "{\"path\":\"" + file + "\"}"));
            ToolResultBlock edited = run(executor, session, rt, new ToolUseBlock(CallId.of("e2"),
                "fs_edit", json(file, "old_string", "external", "new_string", "v3")));
            assertThat(edited.isError()).isFalse();
            assertThat(Files.readString(file)).isEqualTo("v3");
        }
    }

    /** it21 P5：写入面同样受保护（写过 → 外部改写 → 再写被拒）。 */
    @Test
    void staleReadAlsoRejectsWrite() throws Exception {
        try (Runtime rt = boot(dir)) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Path file = dir.resolve("note.txt");
            Session session = Session.create(Session.newId("stale-write"), null, null);
            run(executor, session, rt, new ToolUseBlock(CallId.of("w1"), "fs_write",
                json(file, "content", "v1")));

            Files.writeString(file, "external");

            ToolResultBlock rejected = run(executor, session, rt, new ToolUseBlock(CallId.of("w2"),
                "fs_write", json(file, "content", "v2")));
            assertThat(rejected.isError()).isTrue();
            assertThat(rejected.content()).contains("file changed since read");
            assertThat(Files.readString(file)).isEqualTo("external");
        }
    }

    /** it21 边界：从未经 fs 工具读过/写过的文件 → 维持现行为（无事实即无保护）。 */
    @Test
    void editOfNeverTouchedFileKeepsCurrentBehaviour() throws Exception {
        try (Runtime rt = boot(dir)) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Path file = dir.resolve("plain.txt");
            Files.writeString(file, "x old z");
            Session session = Session.create(Session.newId("plain"), null, null);

            ToolResultBlock edited = run(executor, session, rt, new ToolUseBlock(CallId.of("e1"),
                "fs_edit", json(file, "old_string", "old", "new_string", "new")));

            assertThat(edited.isError()).isFalse();
            assertThat(Files.readString(file)).isEqualTo("x new z");
        }
    }

    /** it21 跨进程：事实在日志——新进程只有重放的事件，无任何进程内状态，保护仍在。 */
    @Test
    void protectionSurvivesResumeFromLogSeed() throws Exception {
        Path file = dir.resolve("note.txt");
        List<SessionEvent> seed;
        try (Runtime rt = boot(dir)) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("resume"), null, null);
            run(executor, session, rt, new ToolUseBlock(CallId.of("w1"), "fs_write",
                json(file, "content", "v1")));
            run(executor, session, rt, new ToolUseBlock(CallId.of("r1"), "fs_read",
                "{\"path\":\"" + file + "\"}"));
            seed = session.events().stream().map(entry -> (SessionEvent) entry.event()).toList();
        }
        Files.writeString(file, "external");

        try (Runtime resumed = boot(dir)) {
            ToolExecutor executor = resumed.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("resume"), seed, null);
            ToolResultBlock rejected = run(executor, session, resumed, new ToolUseBlock(
                CallId.of("e1"), "fs_edit", json(file, "old_string", "external", "new_string", "x")));

            assertThat(rejected.isError()).isTrue();
            assertThat(rejected.content()).contains("file changed since read");
        }
    }

    /** it21 搜索：结果行 `路径:行号:行文本` 经 pipeline 显形，截断以尾行标注（只读工具，计划模式亦放行）。 */
    @Test
    void searchSurfacesPathLineTextAndTruncationThroughPipeline() throws Exception {
        try (Runtime rt = boot(dir)) {
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Files.writeString(dir.resolve("a.txt"), "needle one\nplain");
            Files.writeString(dir.resolve("sub.txt"), "x\nneedle two");
            Session session = Session.create(Session.newId("search"), null, null);
            session.append(new PlanModeEvent(1, true));   // 只读面：计划模式不拦

            ToolResultBlock result = run(executor, session, rt,
                new ToolUseBlock(CallId.of("s1"), "fs_search",
                    "{\"pattern\":\"needle\",\"path\":\".\"}"));

            assertThat(result.isError()).isFalse();
            assertThat(result.content()).isEqualTo("a.txt:1:needle one\nsub.txt:2:needle two");
        }
    }

    /** it21 搜索有界：匹配上限经行配置生效，超出以尾行标注（不静默丢）。 */
    @Test
    void cappedSearchAppendsTruncationTail() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "fs-local".equals(id)
                ? Map.of("root", dir.toString(), "searchMaxMatches", 1L) : Map.of());
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
                new SystemPromptPlugin(),
                new PlanModePlugin("Plan mode guidance (test)."), new SandboxLocalPlugin(),
                new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, dir)),
                new FsLocalPlugin(), new FsToolPlugin()));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Files.writeString(dir.resolve("a.txt"), "needle");
            Files.writeString(dir.resolve("b.txt"), "needle");
            Session session = Session.create(Session.newId("capped-search"), null, null);

            ToolResultBlock result = run(executor, session, rt,
                new ToolUseBlock(CallId.of("s1"), "fs_search",
                    "{\"pattern\":\"needle\",\"path\":\".\"}"));

            assertThat(result.isError()).isFalse();
            assertThat(result.content()).isEqualTo("a.txt:1:needle\n… (search truncated)");
        }
    }

    /** 标准组合（含 fs 工具）与执行单次调用。 */
    private static Runtime boot(Path root) {
        Runtime rt = new Runtime();
        new PluginLoader().loadAll(rt, List.of(
            new SessionStorePlugin(), new ApprovalAutoPlugin(), new ToolsPlugin(),
            new SystemPromptPlugin(),
            new PlanModePlugin("Plan mode guidance (test)."), new SandboxLocalPlugin(),
            new SandboxPolicyPlugin(new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, root)),
            new FsLocalPlugin(root), new FsToolPlugin()));
        return rt;
    }

    private static ToolResultBlock run(ToolExecutor executor, Session session, Runtime rt,
                                       ToolUseBlock call) {
        return executor.execute(List.of(call), session, 0, 0, rt.root(), AbortSignal.never())
            .getFirst().event().block();
    }

    private static String json(Path path, String... keyValues) {
        StringBuilder json = new StringBuilder("{\"path\":\"").append(path).append('"');
        for (int i = 0; i < keyValues.length; i += 2) {
            json.append(",\"").append(keyValues[i]).append("\":\"").append(keyValues[i + 1]).append('"');
        }
        return json.append('}').toString();
    }
}
