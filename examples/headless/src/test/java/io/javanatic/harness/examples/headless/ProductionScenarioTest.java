package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.agentloop.RequestFingerprints;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.event.CompactionEnd;
import io.javanatic.harness.session.event.CompactionStart;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalService;
import io.javanatic.harness.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * it15 生产模拟场景（replay 驱动:keyless、确定性）:AppBoot 以 PRODUCTION 组合
 * boot+verify → boot 后注入 {@link ReplayPlugin} → 多步工具任务（人闸真 stdin 放行）
 * → 中途 compaction ×2（turn1 / resume 后的 turn2 各一次）→ 重启（新 Runtime 载盘）
 * resume → budget 累积超限优雅停 → 终局第三个 Runtime 逐锚点前缀折叠重建请求（R1 全比对）。
 *
 * <p>数值钉（it20 修正,末次 inputTokens 口径）:maxContextTokens=100、retainTokens=1、
 * maxBudgetTokens=40、单条 outputTokens=10;脚本 leg1=[A(60),C(150),S1,D(60)]、
 * leg2=[E(150),S2,F(60)]——跨阈在 C（turn1 step2 顶压缩 #1）与 E（turn2 step1 顶压缩 #2）；
 * 累计 output 50（5 条 assistant）在 turn3 起始越限。压缩后各请求回落到 60（末次口径下
 * 不再复发——旧脚本依赖的 max() 高位水位已随 it20 移除）。</p>
 */
class ProductionScenarioTest {

    private static final long COMPACT_AT = 100;
    private static final long BUDGET = 40;
    private static final long STEP_OUTPUT = 10;
    private static final int TOOL_CALLS = 3;
    private static final String SESSION = "it15-scenario";
    private static final String PROMPT = "You are the production-scenario replay agent.";

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @TempDir
    Path configDir;

    @Test
    void productionScenarioCompactsRestartsAndRebuildsEveryAnchor() throws Exception {
        Path note = workspace.resolve("note.txt");
        Files.writeString(note, "scenario note");
        Path written = workspace.resolve("written.txt");
        Path profile = Files.writeString(configDir.resolve("profile.yml"),
            "name: scenario\npolicy: production\nbundles: [base]\nrows: []\n");

        // 审批脚本:人闸 stdin 每次 ask 新建 BufferedReader——逐行喂入防读穿;
        // 行数 = 全程工具调用数,是场景脚本的一部分,末尾断言恰好耗尽
        DripStdin stdin = new DripStdin(TOOL_CALLS);
        InputStream original = System.in;
        System.setIn(stdin);
        try {
            // leg1:PRODUCTION boot(verify)+ 注入 replay → 多步任务 → 中途压缩 #1
            try (Runtime rt = bootProduction(profile, leg1Scripts(note, written))) {
                assertThat(rt.root().require(ApprovalService.KEY).mode())
                    .isEqualTo(ApprovalService.Mode.HUMAN_GATE);
                AgentHandle handle = rt.root().require(AgentRegistry.KEY).create(rt.root(),
                    CreateAgentOptions.of(Session.newId(SESSION), new AgentOptions("replay", "m")));
                handle.agent().followup(UserMessage.of("读笔记,写文件,收尾", new MessageSource.User()));
                handle.agent().whenIdle().join();
                disposeAndSave(rt, handle);
            }
            // leg2:新 Runtime 载盘 resume(turn2:读回写入的文件 + 压缩 #2)+ leg3:budget 优雅停
            try (Runtime rt = bootProduction(profile, leg2Scripts(written))) {
                AgentHandle handle = resumeAgent(rt);
                handle.agent().followup(UserMessage.of("续跑:读回写的文件", new MessageSource.User()));
                handle.agent().whenIdle().join();
                handle.agent().followup(UserMessage.of("再跑一轮", new MessageSource.User()));
                handle.agent().whenIdle().join();
                disposeAndSave(rt, handle);
            }
            // 终局:第三个 Runtime(无 agent)载盘,逐锚点前缀折叠 R1 全比对
            try (Runtime rt = bootProduction(profile, List.of())) {
                assertScenario(rt, load(rt));
            }
            assertThat(stdin.served()).isEqualTo(TOOL_CALLS);
        } finally {
            System.setIn(original);
        }
    }

    // ────────── 装配 ──────────

    /** 生产组合:profile(policy: production)+ CLI overlays + 场景钉;boot 后注入 replay(组合真相不变)。 */
    private Runtime bootProduction(Path profile, List<List<StreamChunk>> scripts) throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {
            "scenario", "--policy=PRODUCTION", "--approval=ask"});
        List<ConfigRowSpec> overlays = new ArrayList<>(
            HeadlessMain.buildOverlays(options, workspace, sessions));
        // keyless 确定性:覆盖 env 表达式恒禁外部 LLM 行(禁用行仍计入双向校验)
        overlays.add(new ConfigRowSpec.Replace("llm-openai-compat",
            Map.of("name", "deepseek", "baseUrl", "https://api.deepseek.com"), "true"));
        // it13 挂账:budget 经 overlay 进生产组合(S2 --budget= 同形)
        overlays.add(new ConfigRowSpec.Replace("loop-guard", Map.of("maxBudgetTokens", BUDGET), null));
        // 压缩启用:阈值=模型绝对窗口,保留预算小到尾部必可压动
        overlays.add(new ConfigRowSpec.Replace("compaction",
            Map.of("maxContextTokens", COMPACT_AT, "retainTokens", 1L), null));
        Runtime rt = AppBoot.boot(new AppBoot.BootOptions(profile, overlays, true, Policy.PRODUCTION));
        try {
            new PluginLoader().loadAllUnder(rt.root(), List.of(new ReplayPlugin(scripts)));
        } catch (RuntimeException e) {
            rt.close();
            throw e;
        }
        registerPrompt(rt);
        return rt;
    }

    /** 提示词 = 静态段 + 动态段(工具结果计数)——逐锚点变化,折叠必须重建当时的日志状态。 */
    private static void registerPrompt(Runtime rt) {
        SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
        prompts.register(new PromptSection.Static(0, PROMPT));
        prompts.register(new PromptSection.Dynamic(10, session -> "tool results so far: "
            + session.events().stream().map(LoggedEvent::event)
                .filter(ToolResultEvent.class::isInstance).count()));
    }

    /** leg1 脚本:A(input=60)fs_read → C(input=150 跨阈)fs_write → S1 压缩摘要 → D 终答。 */
    private static List<List<StreamChunk>> leg1Scripts(Path note, Path written) {
        return List.of(
            List.of(new StreamChunk.Delta("读"),
                new StreamChunk.DeltaToolUse(CallId.of("c1"), "fs_read",
                    "{\"path\":\"" + note + "\"}"),
                new StreamChunk.Usage(new TokenUsage(60, STEP_OUTPUT, 0)),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(new StreamChunk.Delta("写"),
                new StreamChunk.DeltaToolUse(CallId.of("c2"), "fs_write",
                    "{\"path\":\"" + written + "\",\"content\":\"written by scenario\"}"),
                new StreamChunk.Usage(new TokenUsage(150, STEP_OUTPUT, 0)),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            summaryScript("S1"),
            List.of(new StreamChunk.Delta("第一轮完成"),
                new StreamChunk.Usage(new TokenUsage(60, STEP_OUTPUT, 0)),
                new StreamChunk.Finish(FinishReason.STOP)));
    }

    /** leg2 脚本:turn2 step0 E(input=150 跨阈)fs_read → step1 顶压缩 #2(S2)→ F 终答。 */
    private static List<List<StreamChunk>> leg2Scripts(Path written) {
        return List.of(
            List.of(new StreamChunk.Delta("读"),
                new StreamChunk.DeltaToolUse(CallId.of("c3"), "fs_read",
                    "{\"path\":\"" + written + "\"}"),
                new StreamChunk.Usage(new TokenUsage(150, STEP_OUTPUT, 0)),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            summaryScript("S2"),
            List.of(new StreamChunk.Delta("第二轮完成"),
                new StreamChunk.Usage(new TokenUsage(60, STEP_OUTPUT, 0)),
                new StreamChunk.Finish(FinishReason.STOP)));
    }

    private static List<StreamChunk> summaryScript(String marker) {
        return List.of(
            new StreamChunk.Delta("## Primary Request and Intent\n- " + marker + "-checkpoint"),
            new StreamChunk.Finish(FinishReason.STOP));
    }

    // ────────── 生命周期 ──────────

    private static SessionPersistence.Loaded load(Runtime rt) throws Exception {
        return rt.root().require(SessionPersistence.KEY).load(Session.newId(SESSION));
    }

    private static AgentHandle resumeAgent(Runtime rt) throws Exception {
        SessionPersistence.Loaded loaded = load(rt);
        rt.root().require(SessionStore.KEY).create(rt.root(), Session.newId(SESSION),
            new CreateOptions(loaded.events(), loaded.header()));
        return rt.root().require(AgentRegistry.KEY).resume(rt.root(),
            new ResumeAgentOptions(Session.newId(SESSION), new AgentOptions("replay", "m")));
    }

    private static void disposeAndSave(Runtime rt, AgentHandle handle) throws Exception {
        Agent agent = handle.agent();
        handle.disposeAndAwait();
        rt.root().require(SessionPersistence.KEY).save(agent.session());
    }

    // ────────── 终局断言 ──────────

    private void assertScenario(Runtime rt, SessionPersistence.Loaded loaded) {
        List<SessionEvent> events = loaded.events();
        List<String> types = events.stream().map(SessionEvent::type).toList();
        List<Integer> anchorIndexes = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof LlmRequestEvent) {
                anchorIndexes.add(i);
            }
        }
        List<CompactionStart> starts = collect(events, CompactionStart.class);
        List<CompactionEnd> ends = collect(events, CompactionEnd.class);
        List<CompactionSummary> summaries = collect(events, CompactionSummary.class);
        List<ToolResultEvent> toolResults = collect(events, ToolResultEvent.class);
        List<TurnStart> turns = collect(events, TurnStart.class);
        List<TurnEnd> turnEnds = collect(events, TurnEnd.class);

        // ① 多步工具任务:3 次工具调用全部放行真跑(turn1 两次,turn2 一次)
        assertThat(toolResults).hasSize(TOOL_CALLS);
        assertThat(toolResults).allSatisfy(result -> assertThat(result.block().isError()).isFalse());
        assertThat(toolResults.get(0).block().content()).contains("scenario note");
        assertThat(toolResults.getLast().block().content()).contains("written by scenario");
        assertThat(toolResults.stream().filter(result -> result.turn() == 1).count()).isEqualTo(2);
        assertThat(toolResults.stream().filter(result -> result.turn() == 2).count()).isEqualTo(1);

        // ② 中途压缩 ×2:事务成对无错,摘要走 replay 路由;第一次在 turn1 锚点 C 与 D 之间
        assertThat(starts).hasSize(2);
        assertThat(ends).hasSize(2);
        assertThat(ends).allSatisfy(end -> assertThat(end.error()).isNull());
        assertThat(summaries).hasSize(2);
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.provider()).isEqualTo("replay");
            assertThat(summary.model()).isEqualTo("m");
        });
        assertThat(summaries.get(0).summary()).contains("S1-checkpoint");
        assertThat(summaries.get(1).summary()).contains("S2-checkpoint");
        assertThat(starts.get(0).turn()).isEqualTo(1);
        assertThat(starts.get(1).turn()).isEqualTo(2);
        List<Integer> startIndexes = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if ("compaction/start".equals(types.get(i))) {
                startIndexes.add(i);
            }
        }
        int c1 = startIndexes.get(0);
        int c2 = startIndexes.get(1);
        assertThat(anchorIndexes.get(1)).isLessThan(c1);
        assertThat(c1).isLessThan(anchorIndexes.get(2));  // #1:C 之后、D 之前(turn1 step2 顶)
        assertThat(anchorIndexes.get(3)).isLessThan(c2);  // #2:E(input=150)之后(turn2 step1 顶)
        assertThat(c2).isLessThan(anchorIndexes.get(4));  // #2 先于 F
        // 摘要成为模型可见背景(投影首条为 Compaction source)
        Session view = Session.create(Session.newId("view"), events, loaded.header());
        assertThat(view.deriveMessages().getFirst().source())
            .isInstanceOf(MessageSource.Compaction.class);

        // ③ 重启 resume:轮号从日志连续 [1,2,3],resume 分界标记在案
        assertThat(turns).extracting(TurnStart::turn).containsExactly(1, 2, 3);
        assertThat(types).contains("session/end-seed");

        // ④ budget 优雅停:turn3 起始拒 → Error 关轮,start/end 配对无悬挂
        assertThat(turnEnds).hasSize(3);
        assertThat(turnEnds.get(0).reason()).isInstanceOf(TurnEndReason.Completed.class);
        assertThat(turnEnds.get(1).reason()).isInstanceOf(TurnEndReason.Completed.class);
        TurnEnd last = turnEnds.get(2);
        assertThat(last.turn()).isEqualTo(3);
        assertThat(last.reason()).isInstanceOf(TurnEndReason.Error.class);
        assertThat(((TurnEndReason.Error) last.reason()).message())
            .contains("token budget exceeded: 50 > 40 (cumulative output tokens)");

        // ⑤ R1 全比对:逐锚点前缀折叠重建请求(提示词动态段逐锚点变化,非静态恒等)
        SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
        String schemaFp = RequestFingerprints.sha256(RequestFingerprints.toolSchemaFingerprint(
            rt.root().require(ToolRegistry.KEY).schemas(rt.root())));
        assertThat(anchorIndexes).hasSize(5);
        List<LlmRequestEvent> anchors = collect(events, LlmRequestEvent.class);
        assertThat(anchors.stream().map(LlmRequestEvent::systemPromptSha256).distinct().count())
            .isGreaterThan(1L);
        for (int index : anchorIndexes) {
            LlmRequestEvent anchor = (LlmRequestEvent) events.get(index);
            assertThat(anchor.messagesFromSeq()).isZero();
            assertThat(anchor.messagesToSeq()).isEqualTo(index - 1L);
            Session folded = Session.create(Session.newId("fold"),
                events.subList(0, index), loaded.header());
            assertThat(RequestFingerprints.sha256(prompts.assemble(folded)))
                .as("anchor seq %s 提示词重建", index)
                .isEqualTo(anchor.systemPromptSha256());
            assertThat(schemaFp)
                .as("anchor seq %s schema 指纹", index)
                .isEqualTo(anchor.toolsSchemaSha256());
        }
    }

    private static <T extends SessionEvent> List<T> collect(List<SessionEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** 审批放行脚本:一次一行,行内不读穿(ApprovalPrompt.stdin 每次 ask 新建 BufferedReader)。 */
    private static final class DripStdin extends InputStream {

        private final Deque<byte[]> lines = new ArrayDeque<>();
        private byte[] current;
        private int offset;
        private int served;

        DripStdin(int yesLines) {
            for (int i = 0; i < yesLines; i++) {
                lines.add("y\n".getBytes(StandardCharsets.UTF_8));
            }
        }

        synchronized int served() {
            return served;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (current == null) {
                current = lines.poll();
                offset = 0;
                if (current == null) {
                    return -1; // 脚本耗尽:EOF(拒绝语义,fail-closed)
                }
                served++;
            }
            int n = Math.min(len, current.length - offset);
            System.arraycopy(current, offset, b, off, n);
            offset += n;
            if (offset == current.length) {
                current = null;
            }
            return n;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }
    }
}
