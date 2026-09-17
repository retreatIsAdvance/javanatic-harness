package io.javanatic.harness.todo;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * todo_write 行为：整表替换快照、审计对之间的领域事件序、校验矩阵（dsh 同款
 * 消息形状）、必配 config fail loud、并行批次交错下配对靠 callId（相邻性非契约）。
 */
class TodoPluginTest {

    @TempDir
    Path root;

    private static final String ONE_TODO =
        "{\"todos\":[{\"content\":\"  step one  \",\"status\":\"in_progress\"}]}";

    @Test
    void snapshotLandsBetweenAuditPairWithCanonicalItems() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(), new TodoPlugin(false)));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            Session session = Session.create(Session.newId("todo"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("w1"), "todo_write", ONE_TODO)),
                session, 0, 0, rt.root(), AbortSignal.never());

            assertThat(results.getFirst().event().block().content())
                .isEqualTo("Updated todo list: 0 pending, 1 in progress, 0 completed.");
            // 领域事件夹在审计对之间：tool/call → todo/write → tool/result
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsExactly("tool/call", "todo/write", "tool/result");
            TodoWriteEvent snapshot = (TodoWriteEvent) session.events().get(1).event();
            // canonical 形：content 已 trim
            assertThat(snapshot.todos()).containsExactly(new TodoItem("step one", TodoStatus.IN_PROGRESS));
            assertThat(snapshot.ignorable()).isFalse();
        }
    }

    @Test
    void validationMatrixRejectsAndLeavesNoSnapshot() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(), new TodoPlugin(false)));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            Session session = Session.create(Session.newId("reject"), null, null);

            List<String> bad = List.of(
                "{\"todos\":[{\"content\":\"   \",\"status\":\"pending\"}]}",
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\"},"
                    + "{\"content\":\"a\",\"status\":\"completed\"}]}",
                "{\"todos\":[{\"content\":\"a\",\"status\":\"paused\"}]}",
                "{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\"},"
                    + "{\"content\":\"b\",\"status\":\"in_progress\"}]}");
            int call = 0;
            for (String arguments : bad) {
                List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                    new ToolUseBlock(CallId.of("bad" + call), "todo_write", arguments)),
                    session, 0, call, rt.root(), AbortSignal.never());
                assertThat(results.getFirst().event().block().isError()).isTrue();
                call++;
            }
            // 拒绝仍成对留痕，但一条 todo/write 都没有
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsOnly("tool/call", "tool/result")
                .hasSize(2 * bad.size());
        }
    }

    @Test
    void parallelDeploymentAllowsMultipleInProgress() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(), new TodoPlugin(true)));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            Session session = Session.create(Session.newId("parallel"), null, null);
            List<LoggedEvent<ToolResultEvent>> results = executor.execute(List.of(
                new ToolUseBlock(CallId.of("p1"), "todo_write",
                    "{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"b\",\"status\":\"in_progress\"}]}")),
                session, 0, 0, rt.root(), AbortSignal.never());

            assertThat(results.getFirst().event().block().isError()).isFalse();
            assertThat(session.events().stream().map(LoggedEvent::type))
                .containsExactly("tool/call", "todo/write", "tool/result");
        }
    }

    @Test
    void missingParallelConfigFailsLoud() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            assertThatThrownBy(() ->
                new PluginLoader().loadAll(rt, List.of(new ApprovalAutoPlugin(), new ToolsPlugin(),
                    new TodoPlugin())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plugin failed and rolled back: todo")
                .cause()
                .hasMessageContaining("allowParallelInProgress");
        }
    }

    /**
     * 并发契约（Session.append Javadoc）：同批并行工具的日志交错序任意——
     * 慢工具的 tool/call 与 tool/result 之间隔着别的事件，配对仍靠 callId
     * 成立；相邻性不是契约，钉进执行门禁。交错因果双向闭合，不赌调度时窗：
     * 慢工具等快工具结果落账再返回，门控工具等慢工具 tool/call 落账再返回，
     * 慢工具再等门控结果——slow.call &lt; gate.result &lt; slow.result 恒成立。
     * 单向因果曾留窗口：慢 worker 起步晚于快工具全序列时 awaitResult 立即
     * 返回、审计对成相邻（it15 收尾全量两连红实录）。任一串行执行序必令某次
     * 等待超时，门禁红。
     */
    @Test
    void parallelBatchInterleavesAndPairsByCallIdNotAdjacency() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new ApprovalAutoPlugin(), new ToolsPlugin(), new TodoPlugin(false)));
            ToolRegistry registry = rt.root().require(ToolRegistry.KEY);
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);
            registry.register(rt.root(), ToolDefinition.of("slow_echo", "慢工具",
                new ValueSchema.Object("参数",
                    Map.of("text", new ValueSchema.Str("文本"))),
                (args, context) -> {
                    awaitResult(context.session(), "fast", Duration.ofSeconds(10));
                    awaitResult(context.session(), "gate", Duration.ofSeconds(10));
                    return ToolExecutionResult.success(args.readString("text"));
                }));
            registry.register(rt.root(), ToolDefinition.of("gate_echo", "门控工具",
                new ValueSchema.Object("参数",
                    Map.of("text", new ValueSchema.Str("文本"))),
                (args, context) -> {
                    awaitCall(context.session(), "slow", Duration.ofSeconds(10));
                    return ToolExecutionResult.success(args.readString("text"));
                }));

            Session session = Session.create(Session.newId("mix"), null, null);
            executor.execute(List.of(
                new ToolUseBlock(CallId.of("slow"), "slow_echo", "{\"text\":\"later\"}"),
                new ToolUseBlock(CallId.of("fast"), "todo_write", ONE_TODO),
                new ToolUseBlock(CallId.of("gate"), "gate_echo", "{\"text\":\"gate\"}")),
                session, 0, 0, rt.root(), AbortSignal.never());

            List<LoggedEvent<?>> log = session.events();
            int callSlow = indexOfCall(log, "slow_echo");
            int resultSlow = indexOfResult(log, "slow");
            // 交错确凿：slow.call < gate.result < slow.result 恒成立（门控等 slow 的
            // call 落账才返回、slow 又等门控的 result），审计对之间必隔事件
            assertThat(resultSlow - callSlow).isGreaterThan(1);
            assertThat(log.stream().map(LoggedEvent::type))
                .containsExactlyInAnyOrder("tool/call", "tool/call", "tool/call", "todo/write",
                    "tool/result", "tool/result", "tool/result");
            // 配对靠内容：slow 的 result 以 callId 定位，内容正确
            ToolResultEvent slowResult = (ToolResultEvent) log.get(resultSlow).event();
            assertThat(slowResult.block()).isEqualTo(new ToolResultBlock(CallId.of("slow"), "later", false));
        }
    }

    /** 阻塞至该 callId 的 tool/call 落账；语义同 {@link #awaitResult}。 */
    private static void awaitCall(Session session, String callId, Duration timeout) {
        await(session, timeout, "tool/call " + callId, event -> event instanceof ToolCallEvent call
            && call.callId().value().equals(callId));
    }

    /** 阻塞至该 callId 的 tool/result 落账。 */
    private static void awaitResult(Session session, String callId, Duration timeout) {
        await(session, timeout, "tool/result " + callId, event -> event instanceof ToolResultEvent result
            && result.block().toolUseId().value().equals(callId));
    }

    /**
     * 轮询直至断言成立。AssertionError 不走 executor 的 Exception 网，
     * 超时必炸——串行执行或门控失效都在此现形。
     */
    private static void await(Session session, Duration timeout, String what,
                              Predicate<SessionEvent> match) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (session.events().stream().map(LoggedEvent::event).anyMatch(match)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting " + what, e);
            }
        }
        throw new AssertionError(what + " not appended within " + timeout);
    }

    @Test
    void snapshotRoundTripsThroughJsonlViaServiceLoaderCodec() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(root),
                new ApprovalAutoPlugin(), new ToolsPlugin(), new TodoPlugin(false)));
            ToolExecutor executor = rt.root().require(ToolExecutor.KEY);

            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("durable"),
                CreateOptions.empty());
            executor.execute(List.of(
                new ToolUseBlock(CallId.of("d1"), "todo_write", ONE_TODO)),
                session, 1, 0, rt.root(), AbortSignal.never());
            store.flush(rt.root(), session);

            SessionPersistence.Loaded loaded =
                rt.root().require(SessionPersistence.KEY).load(session.id());
            TodoWriteEvent original = session.events().stream()
                .map(LoggedEvent::event)
                .filter(TodoWriteEvent.class::isInstance)
                .map(TodoWriteEvent.class::cast)
                .findFirst().orElseThrow();
            TodoWriteEvent reloaded = loaded.events().stream()
                .filter(TodoWriteEvent.class::isInstance)
                .map(TodoWriteEvent.class::cast)
                .findFirst().orElseThrow();
            assertThat(reloaded).isEqualTo(original);
        }
    }

    /** 按 name 定位 tool/call 下标（配对靠内容，不靠相邻性的消费示例）。 */
    private static int indexOfCall(List<LoggedEvent<?>> log, String toolName) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).event() instanceof ToolCallEvent call
                && call.name().equals(toolName)) {
                return i;
            }
        }
        throw new AssertionError("no tool/call for " + toolName);
    }

    /** 按 callId 定位 tool/result 下标。 */
    private static int indexOfResult(List<LoggedEvent<?>> log, String callId) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).event() instanceof ToolResultEvent result
                && result.block().toolUseId().value().equals(callId)) {
                return i;
            }
        }
        throw new AssertionError("no tool/result for " + callId);
    }
}
