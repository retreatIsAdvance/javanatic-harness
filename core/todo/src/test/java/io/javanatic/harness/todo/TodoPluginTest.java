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
     * 慢工具的 tool/call 与 tool/result 之间隔着快工具的事件，配对仍靠
     * callId 成立；相邻性不是契约，钉进执行门禁。交错做成因果（慢工具等
     * 快工具的结果落账再返回），不赌调度时窗——CI 单核 runner 上固定 sleep
     * 曾因调度饥饿偶发红（it12.6 收尾 CI 实录）。
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
                    return ToolExecutionResult.success(args.readString("text"));
                }));

            Session session = Session.create(Session.newId("mix"), null, null);
            executor.execute(List.of(
                new ToolUseBlock(CallId.of("slow"), "slow_echo", "{\"text\":\"later\"}"),
                new ToolUseBlock(CallId.of("fast"), "todo_write", ONE_TODO)),
                session, 0, 0, rt.root(), AbortSignal.never());

            List<LoggedEvent<?>> log = session.events();
            int callSlow = indexOfCall(log, "slow_echo");
            int resultSlow = indexOfResult(log, "slow");
            // 交错确凿：slow 的审计对之间至少隔着 fast 的一条事件
            assertThat(resultSlow - callSlow).isGreaterThan(1);
            assertThat(log.stream().map(LoggedEvent::type))
                .containsExactlyInAnyOrder("tool/call", "tool/call", "todo/write",
                    "tool/result", "tool/result");
            // 配对靠内容：slow 的 result 以 callId 定位，内容正确
            ToolResultEvent slowResult = (ToolResultEvent) log.get(resultSlow).event();
            assertThat(slowResult.block()).isEqualTo(new ToolResultBlock(CallId.of("slow"), "later", false));
        }
    }

    /** 阻塞至该 callId 的 tool/result 落账；AssertionError 不走 executor 的 Exception 网，超时必炸。 */
    private static void awaitResult(Session session, String callId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean present = session.events().stream()
                .map(LoggedEvent::event)
                .filter(ToolResultEvent.class::isInstance)
                .map(ToolResultEvent.class::cast)
                .anyMatch(event -> event.block().toolUseId().value().equals(callId));
            if (present) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting tool/result " + callId, e);
            }
        }
        throw new AssertionError("tool/result for " + callId + " not appended within " + timeout);
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
