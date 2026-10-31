package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.lsp.LspAnchor;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspDocument;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspObservation;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspRange;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspService;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 回放（it29 停 3 S4）：含 LSP 工具调用的会话只从日志重建请求，零 LSP 触碰、零新事件。
 *
 * <p>两条腿都在手装组合里跑（fake LSP service：一次确定性诊断观察，绝不起进程）：
 * <ol>
 *   <li>live：replay 模型发起 {@code lsp_diagnostics} → 审批 AUTO → 工具结果
 *       （逐字渲染文本，含锚首行）落账 → 存盘；</li>
 *   <li>replay：新 Runtime 只载盘，断言工具结果按原样入账（内容与 live 相同）、
 *       fake LSP service 未被再次触碰（零 LS 进程重建的夹具证据）。</li>
 * </ol>
 * 事件面只出现既有类型（{@code tool/call} + {@code tool/result}），没有 {@code lsp/*}。
 * 结果文本按 {@link io.javanatic.harness.lsp.tool.LspToolOutput} 逐字断言：措辞突变必红。
 */
class LspReplayTest {

    private static final String SESSION = "it29-lsp-replay";
    private static final URI ROOT = URI.create("file:///proj");
    private static final URI DOC = URI.create("file:///proj/src/main/App.java");
    private static final String EXPECTED_RESULT =
        "拉取成功（hash bbbbbbbb，项目代 3，等待窗 16.0s）；诊断 1 条：\n"
        + "  src/main/App.java:11:4 [ERROR] Cannot resolve symbol 'Foo'";

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void lspToolSessionReplaysOfflineFromLogAlone() throws Exception {
        FakeLsp liveLsp = new FakeLsp();

        // ── live：模型调 lsp_diagnostics，结果落账并存盘 ──
        List<SessionEvent> liveEvents;
        String liveResult;
        try (Runtime rt = boot(liveLsp, scripts())) {
            AgentHandle handle = rt.root().require(AgentRegistry.KEY).create(rt.root(),
                CreateAgentOptions.of(Session.newId(SESSION), new AgentOptions("replay", "m")));
            handle.agent().followup(UserMessage.of("看一眼诊断", new MessageSource.User()));
            handle.agent().whenIdle().join();
            Session session = handle.agent().session();
            liveEvents = List.copyOf(session.events().stream().map(LoggedEvent::event).toList());
            liveResult = toolResult(liveEvents).block().content();
            handle.disposeAndAwait();
            rt.root().require(SessionPersistence.KEY).save(session);
        }
        assertThat(liveLsp.calls()).isEqualTo(1);
        // 结果文本逐字断言（锚首行 + 诊断行）：LspToolOutput 措辞突变在此必红
        assertThat(liveResult).isEqualTo(EXPECTED_RESULT);
        // 零新事件：审计仍只有 tool/call + tool/result，没有任何 lsp/* 类型
        assertThat(liveEvents.stream().map(SessionEvent::type))
            .noneMatch(type -> type.startsWith("lsp/"))
            .contains("tool/call", "tool/result");
        assertThat(liveEvents.stream().filter(ToolCallEvent.class::isInstance)
            .map(event -> ((ToolCallEvent) event).name())).containsExactly("lsp_diagnostics");

        // ── replay：新 Runtime 只读盘；fake LSP service 零触碰（零进程重建） ──
        FakeLsp replayLsp = new FakeLsp();
        try (Runtime rt = boot(replayLsp, List.of())) {
            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(Session.newId(SESSION));
            List<SessionEvent> events = loaded.events();

            // 工具结果按原样入账（JSONL 往返逐字相等）
            assertThat(toolResult(events).block().content()).isEqualTo(liveResult);
            assertThat(events).containsExactlyElementsOf(liveEvents);
            // 回放路径零 LSP 触碰：结果只来自日志，不重查 LS（等价零进程）
            assertThat(replayLsp.calls()).isZero();
        }
    }

    private static ToolResultEvent toolResult(List<SessionEvent> events) {
        return events.stream().filter(ToolResultEvent.class::isInstance)
            .map(ToolResultEvent.class::cast).findFirst().orElseThrow();
    }

    /** replay 脚本：turn1 调 lsp_diagnostics，turn2 收尾。 */
    private static List<List<StreamChunk>> scripts() {
        return List.of(
            List.of(new StreamChunk.Delta("看诊断"),
                new StreamChunk.DeltaToolUse(CallId.of("l1"), "lsp_diagnostics",
                    "{\"document\":\"/proj/src/main/App.java\"}"),
                new StreamChunk.Usage(new TokenUsage(10, 5, 0)),
                new StreamChunk.Finish(FinishReason.TOOL_USE)),
            List.of(new StreamChunk.Delta("已读"),
                new StreamChunk.Usage(new TokenUsage(10, 5, 0)),
                new StreamChunk.Finish(FinishReason.STOP)));
    }

    /**
     * 手装最小组合（无 base bundle）：会话/持久化/agent/loop-guard/llm/提示词/审批/工具 +
     * fake LSP service + lsp-tool + replay + agent-loop。行序即依赖序。
     */
    private Runtime boot(FakeLsp lsp, List<List<StreamChunk>> scripts) {
        Runtime rt = new Runtime();
        rt.root().provide(ConfigService.KEY, id -> Map.of());
        Map<String, Plugin> discovered = new PluginLoader().discover();
        List<Plugin> ordered = List.of(
            plugin(discovered, "session-store"), new JsonlPersistencePlugin(sessions),
            plugin(discovered, "agents"), plugin(discovered, "loop-guard"),
            plugin(discovered, "system-prompt"), plugin(discovered, "llm"),
            plugin(discovered, "approval-auto"), plugin(discovered, "tools"),
            lsp, plugin(discovered, "lsp-tool"), new ReplayPlugin(scripts),
            plugin(discovered, "agent-loop"));
        try {
            new PluginLoader().loadAll(rt, ordered);
        } catch (RuntimeException e) {
            rt.close();
            throw e;
        }
        return rt;
    }

    private static Plugin plugin(Map<String, Plugin> discovered, String id) {
        Plugin plugin = discovered.get(id);
        if (plugin == null) {
            throw new IllegalStateException("plugin not discovered: " + id);
        }
        return plugin;
    }

    /**
     * 确定性 fake：一次成功诊断观察，绝不联网/起进程；记录 LSP 触碰次数。
     * definition/references 未接线（本回放夹具只走 lsp_diagnostics）。
     */
    private static final class FakeLsp implements Plugin {

        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public String id() {
            return "fake-lsp";
        }

        @Override
        public Set<String> requires() {
            return Set.of();
        }

        @Override
        public void apply(Scope scope) {
            scope.provide(LspService.KEY, new LspService() {
                @Override
                public LspResult<LspDiagnostic> diagnostics(LspQuery request, AbortSignal signal) {
                    calls.incrementAndGet();
                    LspAnchor anchor = new LspAnchor(ROOT,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), 3,
                        "b".repeat(64), new LspDocument(DOC, Optional.of("a".repeat(64))));
                    LspObservation observation = new LspObservation(anchor, anchor,
                        Duration.ofSeconds(15), Duration.ofSeconds(16),
                        Instant.parse("2026-10-31T00:00:00Z"),
                        new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 7,
                            OptionalInt.empty()));
                    LspDiagnostic diagnostic = new LspDiagnostic(
                        new LspRange(new LspPosition(11, 4), new LspPosition(11, 10)),
                        LspDiagnostic.Severity.ERROR, Optional.empty(), Optional.empty(),
                        "Cannot resolve symbol 'Foo'");
                    return new LspResult.Observed<>(observation, List.of(diagnostic));
                }

                @Override
                public LspResult<LspLocation> definition(LspPositionQuery request,
                                                         AbortSignal signal) {
                    calls.incrementAndGet();
                    throw new UnsupportedOperationException(
                        "fake lsp：本回放夹具只接线 diagnostics");
                }

                @Override
                public LspResult<LspLocation> references(LspPositionQuery request,
                                                         AbortSignal signal) {
                    calls.incrementAndGet();
                    throw new UnsupportedOperationException(
                        "fake lsp：本回放夹具只接线 diagnostics");
                }

                @Override
                public void close() {
                    // 无资源可回收
                }
            });
        }
    }
}
