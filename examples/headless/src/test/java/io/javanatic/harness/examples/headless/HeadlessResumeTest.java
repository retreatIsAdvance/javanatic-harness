package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionRecovery;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨进程 resume:进程 A 跑一轮 → 落盘 → 进程 B(新装配)load+resume 续轮号。 */
class HeadlessResumeTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void resumeContinuesTurnNumberingAcrossRuntimes() throws Exception {
        // 进程 A:跑一轮假服务端任务(dispose 后落盘)
        HeadlessMain.RunnerOptions first = HeadlessMain.parse(new String[] {
            "第一轮", "--provider=vendor-a", "--base-url=" + fakeServerUrl(),
            "--api-key=fake"});
        assertThat(HeadlessMain.run(first, workspace, sessions)).isZero();
        String sessionId = findSessionId();
        assertThat(Files.readString(sessions.resolve(sessionId).resolve("log.jsonl")))
            .contains("turn/start");

        // 进程 B:新 Runtime、同会话根 → load → resume 续跑一轮
        HeadlessMain.RunnerOptions second = HeadlessMain.parse(new String[] {
            "--resume=" + sessionId, "--provider=vendor-b", "--base-url=" + fakeServerUrl(),
            "--api-key=fake", "第二轮"});
        assertThat(HeadlessMain.run(second, workspace, sessions)).isZero();
        String log = Files.readString(sessions.resolve(sessionId).resolve("log.jsonl"));
        // 轮号连续:两次 turn/start + resume 分界(session/end-seed 由 Session 构造器标记)
        assertThat(log.split("turn/start", -1).length - 1).isGreaterThanOrEqualTo(2);
        assertThat(log).contains("session/end-seed");
    }

    @Test
    void resumeClosesCrashedTailAndSendsPairedResult() throws Exception {
        // 进程 A:跑一轮,盘上留下完整 turn 1
        HeadlessMain.RunnerOptions first = HeadlessMain.parse(new String[] {
            "第一轮", "--provider=vendor-a", "--base-url=" + fakeServerUrl(), "--api-key=fake"});
        assertThat(HeadlessMain.run(first, workspace, sessions)).isZero();
        String sessionId = findSessionId();

        // 崩溃现场(真 SIGKILL 由 S-c e2e 覆盖):落 turn 2 开头——悬空 tool_use,
        // 无 tool/result、无 step/end、无 turn/end
        int crashTurn;
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            SessionPersistence.Loaded loaded = rt.root().require(SessionPersistence.KEY)
                .load(Session.newId(sessionId));
            Session crashed = rt.root().require(SessionStore.KEY).create(rt.root(),
                Session.newId(sessionId), new CreateOptions(loaded.events(), loaded.header()));
            crashTurn = (int) loaded.events().stream()
                .filter(event -> event instanceof TurnStart).count() + 1;
            crashed.append(new TurnStart(1, crashTurn));
            crashed.append(new StepStart(2, crashTurn, 0));
            crashed.append(new AssistantMessageEvent(3, crashTurn, 0,
                new AssistantMessage(new MessageSource.Model("vendor-a", "fake"),
                    List.of(new ToolUseBlock(CallId.of("crash1"), "fs_read", "{\"path\":\"README.md\"}"))),
                null, new SurfaceOp.Append(), null));
            crashed.append(new ToolCallEvent(4, crashTurn, 0,
                CallId.of("crash1"), "fs_read", "{\"path\":\"README.md\"}"));
        }

        // 进程 B:--resume → 恢复收口 → 首个真实请求携带配对结果(悬空 tool_use 违反 OpenAI 契约)
        LAST_REQUEST_BODY.set(null);
        HeadlessMain.RunnerOptions second = HeadlessMain.parse(new String[] {
            "--resume=" + sessionId, "--provider=vendor-b", "--base-url=" + fakeServerUrl(),
            "--api-key=fake", "第二轮"});
        assertThat(HeadlessMain.run(second, workspace, sessions)).isZero();
        assertThat(LAST_REQUEST_BODY.get())
            .contains("tool_call_id").contains("crash1");

        // 盘上事实:恢复收口在 end-seed 之后追加,sourceEventSeqs 引 seed 前 seq
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            List<SessionEvent> events = rt.root()
                .require(SessionPersistence.KEY).load(Session.newId(sessionId)).events();
            // Loaded.events() 的 seq 即下标(信封连续性由 load 校验)

            int crashMessageSeq = seqOf(events, event ->
                event instanceof AssistantMessageEvent message && message.turn() == crashTurn);
            int firstEndSeedSeq = seqOf(events, event -> event instanceof SessionEndSeedEvent);
            int lastEndSeedSeq = seqOfLast(events, event -> event instanceof SessionEndSeedEvent);
            int recoveredSeq = seqOf(events, event -> event instanceof ToolResultEvent result
                && result.block().toolUseId().equals(CallId.of("crash1")));
            int stepEndSeq = seqOf(events,
                event -> event instanceof StepEnd step && step.turn() == crashTurn);
            int turnEndSeq = seqOf(events,
                event -> event instanceof TurnEnd end && end.turn() == crashTurn);

            ToolResultEvent recovered = (ToolResultEvent) events.get(recoveredSeq);
            assertThat(recovered.block().content()).isEqualTo(SessionRecovery.RESULT_UNKNOWN_TEXT);
            assertThat(recovered.block().isError()).isTrue();
            // 每个生命周期各标一次 end-seed:崩溃尾落在崩溃写者标记之后、resume 标记之前
            assertThat(lastEndSeedSeq).isGreaterThan(firstEndSeedSeq);
            assertThat(crashMessageSeq).isBetween(firstEndSeedSeq, lastEndSeedSeq);
            // sourceEventSeqs 引 seed 前 seq 的锚点是进程内契约(SessionRecoveryTest 钉住);
            // 读回侧该字段不落盘(既有 wire 口径:仅 Replace 事件携带 seq 引用)——归属仍可核:
            // 恢复结果块的 toolUseId = crash1 且位于 resume 标记之后
            assertThat(recoveredSeq).isGreaterThan(lastEndSeedSeq);
            assertThat(stepEndSeq).isGreaterThan(recoveredSeq);
            assertThat(turnEndSeq).isGreaterThan(stepEndSeq);
            TurnEnd interrupted = (TurnEnd) events.get(turnEndSeq);
            assertThat(interrupted.reason())
                .isEqualTo(new TurnEndReason.Aborted(SessionRecovery.INTERRUPTED));
            // 恢复不堵新轮:resume 后的任务在 crashTurn + 1 完成
            assertThat(events).anyMatch(event -> event instanceof TurnEnd end
                && end.turn() == crashTurn + 1
                && end.reason() instanceof TurnEndReason.Completed);
        }
    }

    private static int seqOf(List<SessionEvent> events, Predicate<SessionEvent> match) {
        for (int seq = 0; seq < events.size(); seq++) {
            if (match.test(events.get(seq))) {
                return seq;
            }
        }
        throw new AssertionError("no matching event");
    }

    private static int seqOfLast(List<SessionEvent> events, Predicate<SessionEvent> match) {
        int found = -1;
        for (int seq = 0; seq < events.size(); seq++) {
            if (match.test(events.get(seq))) {
                found = seq;
            }
        }
        if (found < 0) {
            throw new AssertionError("no matching event");
        }
        return found;
    }

    @Test
    void separateRunsGetSeparateSessions() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {
            "任务", "--provider=vendor-a", "--base-url=" + fakeServerUrl(), "--api-key=fake"});
        assertThat(HeadlessMain.run(options, workspace, sessions)).isZero();
        assertThat(HeadlessMain.run(options, workspace, sessions)).isZero();
        try (var dirs = Files.list(sessions)) {
            assertThat(dirs.filter(Files::isDirectory).count()).isEqualTo(2);
        }
    }

    private String findSessionId() throws Exception {
        try (var dirs = Files.list(sessions)) {
            return dirs.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
        }
    }

    /** 起一个本地假 OpenAI 兼容服务端(每个会话一个脚本应答)。 */
    private String fakeServerUrl() {
        return SERVER_URL;
    }

    private static final String SERVER_URL = startServer();

    /** 最近一次收到的请求体(恢复契约断言用:tool_use 是否已配对)。 */
    private static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();

    private static String startServer() {
        try {
            HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                LAST_REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            // java.lang.Runtime 全限定:本作用域 Runtime 是 kernel scope 类型(import 冲突)
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return "http://localhost:" + server.getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
