package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmCallException;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ValueSchema;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolsPlugin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 压缩事务(replay 驱动,keyless):阈值触发/摘要落账/表面收缩/配对完整/R1 不变。 */
class CompactionTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private static final AgentOptions OPTIONS = new AgentOptions("scripted", "m");

    /** 脚本固定报告的 inputTokens(阈值成为唯一变量)。 */
    private static final long REPORTED_INPUT_TOKENS = 10;

    /** 一次成功应答(带高 inputTokens 触发压缩)+ 一次摘要应答。 */
    private static final class Rig implements AutoCloseable {
        final Runtime rt;
        final AgentRegistry agents;
        final AtomicInteger calls = new AtomicInteger();

        Rig(long threshold) {
            rt = new Runtime();
            rt.root().provide(ConfigService.KEY, id -> id.equals("compaction")
                ? threshold < 0
                    ? Map.of("contextWindow", 10, "retainTokens", 20)
                    : Map.of("maxContextTokens", threshold, "retainTokens", 20)
                : Map.of());
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(10, 10)),
                new SystemPromptPlugin(), new LlmPlugin(),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new CompactionPlugin(), new AgentLoopPlugin(FIXED_CLOCK)));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), ToolDefinition.of("echo", "回显",
                new ValueSchema.Object("参数", Map.of()),
                (a, c) -> ToolExecutionResult.success("回")));
            // 脚本 adapter 三段:1) tool_use + 高 inputTokens;2) 压缩摘要;3) 终答
            rt.root().require(LlmService.KEY)
                .registerAdapter("scripted", (config, request, signal) -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        return Stream.of(
                            new StreamChunk.Delta("查"),
                            new StreamChunk.DeltaToolUse(CallId.of("c1"), "echo", "{}"),
                            new StreamChunk.Usage(new TokenUsage(REPORTED_INPUT_TOKENS, 5, 0)),
                            new StreamChunk.Finish(FinishReason.TOOL_USE));
                    }
                    if (call == 2) {
                        return Stream.of(
                            new StreamChunk.Delta("## Primary Request and Intent\n- 任务"),
                            new StreamChunk.Finish(FinishReason.STOP));
                    }
                    return Stream.of(
                        new StreamChunk.Delta("终答"),
                        new StreamChunk.Finish(FinishReason.STOP));
                });
            agents = rt.root().require(AgentRegistry.KEY);
        }

        @Override
        public void close() {
            rt.close();
        }
    }

    @Test
    void pressureCompactionShadowsPrefixAndKeepsTailIntact() throws Exception {
        // 阈值设很低(10):首轮 inputTokens 超阈 → 下一步前压缩
        try (Rig rig = new Rig(5)) {
            AgentHandle handle = rig.agents.create(rig.rt.root(),
                CreateAgentOptions.of(Session.newId("cpt"), OPTIONS));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("任务", new MessageSource.User()));
            agent.whenIdle().join();

            List<String> types = agent.session().events().stream()
                .map(e -> e.event().type()).toList();
            // 事务序列:start → user/message(replace)→ summary → end,后续请求继续
            assertThat(types).containsSubsequence("compaction/start", "user/message",
                "compaction/summary", "compaction/end");
            // 审计事件携带维护调用信封(R1:摘要请求可重建)
            CompactionSummary audit = agent.session().events().stream()
                .map(e -> e.event()).filter(CompactionSummary.class::isInstance)
                .map(CompactionSummary.class::cast).findFirst().orElseThrow();
            assertThat(audit.provider()).isEqualTo("scripted");
            assertThat(audit.summary()).contains("Primary Request");
            // 摘要成为模型可见背景(投影首条为 Compaction source)
            assertThat(agent.session().deriveMessages().getFirst().source())
                .isInstanceOf(MessageSource.Compaction.class);
            // R1:压缩后仍有请求发生且窗口覆盖新表面
            assertThat(types.stream().filter("llm/request"::equals).count()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void ratioPathTriggersFromContextWindow() throws Exception {
        // contextWindow=10 × 默认 0.8 → 阈值 8;报告 10 > 8 触发(比例路径,模型相对)
        try (Rig rig = new Rig(-1)) {
            // Rig(-1) 走 contextWindow 分支:见 Rig 构造里的 -1 哨兵改写
            AgentHandle handle = rig.agents.create(rig.rt.root(),
                CreateAgentOptions.of(Session.newId("cpt3"), OPTIONS));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("任务", new MessageSource.User()));
            agent.whenIdle().join();
            assertThat(agent.session().events().stream()
                .map(e -> e.event().type())).contains("compaction/start");
        }
    }

    @Test
    void missingCapacityFailsLoudAtApply() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            assertThatThrownBy(() -> new PluginLoader().loadAll(
                    rt, List.of(new LlmPlugin(), new CompactionPlugin())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("compaction: no context capacity configured — "
                    + "set contextWindow (thresholdRatio applies) or maxContextTokens; "
                    + "refusing to guess the model's window size");
        }
    }

    @Test
    void belowThresholdDoesNotCompact() throws Exception {
        try (Rig rig = new Rig(100)) {
            AgentHandle handle = rig.agents.create(rig.rt.root(),
                CreateAgentOptions.of(Session.newId("cpt2"), OPTIONS));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("任务", new MessageSource.User()));
            agent.whenIdle().join();
            assertThat(agent.session().events().stream()
                .map(e -> e.event().type())).doesNotContain("compaction/start");
        }
    }

    @Test
    void overflowKindTriggersForcedCompactionAndRetry() throws Exception {
        // 阈值抬到 10 万:压力路径不触发,唯一变量是溢出恢复(it14 改读 Kind 分类)
        try (Rig rig = new Rig(100_000)) {
            AtomicInteger calls = new AtomicInteger();
            rig.rt.root().require(LlmService.KEY).registerAdapter("overflow",
                (config, request, signal) -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        return Stream.of(
                            new StreamChunk.Delta("查"),
                            new StreamChunk.DeltaToolUse(CallId.of("c1"), "echo", "{}"),
                            new StreamChunk.Finish(FinishReason.TOOL_USE));
                    }
                    if (call == 2) {
                        // 适配器已把 400+溢出信号分类成 Kind.OVERFLOW;传输层不重试
                        throw new LlmCallException(LlmCallException.Kind.OVERFLOW,
                            "deepseek http 400: context overflow");
                    }
                    if (call == 3) {
                        return Stream.of(
                            new StreamChunk.Delta("## Primary Request and Intent\n- 任务"),
                            new StreamChunk.Finish(FinishReason.STOP));
                    }
                    return Stream.of(
                        new StreamChunk.Delta("终答"),
                        new StreamChunk.Finish(FinishReason.STOP));
                });

            AgentHandle handle = rig.agents.create(rig.rt.root(),
                CreateAgentOptions.of(Session.newId("ovf"), new AgentOptions("overflow", "m")));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of("任务", new MessageSource.User()));
            agent.whenIdle().join();

            // 1 工具轮 + 1 溢出(不重试) + 1 摘要 + 1 恢复终答
            assertThat(calls.get()).isEqualTo(4);
            List<String> types = agent.session().events().stream()
                .map(e -> e.event().type()).toList();
            assertThat(types).containsSubsequence("compaction/start", "user/message",
                "compaction/summary", "compaction/end");
            // 溢出重试同径:重试只重发请求,工具不重放(it18 ④)
            assertThat(types.stream().filter("tool/call"::equals).count()).isEqualTo(1);
            assertThat(agent.session().events().stream()
                .map(e -> e.event())
                .filter(TurnEnd.class::isInstance)
                .map(e -> ((TurnEnd) e).reason()))
                .containsExactly(new TurnEndReason.Completed());
        }
    }

    @Test
    void toolResultPairingNeverSplit() {
        // 直接驱动插件层的保留边界:构造 assistant(tool_use)→ tool(result) 在尾部
        // 估价极小(retainTokens=20)时边界必须回退越过 tool/result
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY,
                id -> Map.of("maxContextTokens", 10, "retainTokens", 20));
            new PluginLoader().loadAll(rt, List.of(
                new LlmPlugin(), new CompactionPlugin()));
            rt.root().require(LlmService.KEY).registerAdapter("replay",
                (config, request, signal) -> Stream.of(
                    new StreamChunk.Delta("## Next Step\n- (none)"),
                    new StreamChunk.Finish(FinishReason.STOP)));
            CompactionService service = rt.root().require(CompactionService.KEY);
            Session session = Session.create(Session.newId("pair"), null, null);
            session.append(new TurnStart(1, 1));
            session.append(new UserMessageEvent(2,
                UserMessage.of("问", new MessageSource.User()),
                new SurfaceOp.Append(), null));
            session.append(new AssistantMessageEvent(3, 1, 0,
                new AssistantMessage(
                    new MessageSource.Model("replay", "m"),
                    List.of(new ToolUseBlock(
                        CallId.of("c1"), "echo", "{}"))),
                null, new SurfaceOp.Append(), null));
            session.append(new ToolResultEvent(4, 1, 0,
                new ToolResultBlock(
                    CallId.of("c1"), "结果", false),
                false, new SurfaceOp.Append(), null));

            CompactionSummary audit = service.compact(session, 1,
                new LlmCallConfig("replay", "m"),
                AbortSignal.never());
            // 盖写区间只到 user/message(seq 1);assistant+tool/result 配对保留
            assertThat(audit.shadowedStart()).isEqualTo(1);
            assertThat(audit.shadowedEnd()).isEqualTo(1);
        }
    }
}
