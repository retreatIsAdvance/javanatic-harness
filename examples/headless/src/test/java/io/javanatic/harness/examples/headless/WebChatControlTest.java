package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.workbench.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * serve 写面宿主（it38 H2）：真 Agent 轮 → REQUEST 信号租约 → 浏览器审批 →
 * 轮毕 Runtime 回收（政策 (a) 按轮挂载）→ 同会话 send 冷启/复用。
 *
 * <p>矩阵覆盖：放行轮毕退休、deny 文案、同轮多 step 单租约、两并发根会话互不串、
 * 在途 send -32006、cancel 撤租约、停服整树撤销；后台例外（jobs 保留 → send 复用 →
 * 终局退休）与 delegation 子代（父 whenIdle 后仍可审批、父 cancel 不偷撤子租约、
 * 子代终局后整树退休）。
 */
class WebChatControlTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @TempDir
    Path jobsDir;

    private HttpServer server;
    private Runtime probe;
    private final BlockingQueue<String> scripts = new LinkedBlockingQueue<>();
    /** delegation 用例：父/子代请求按 user 文本标记分流，消除并发轮的脚本次序竞态。 */
    private final BlockingQueue<String> parentScripts = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> childScripts = new LinkedBlockingQueue<>();
    private final AtomicReference<Function<String, String>> router = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        probe = new Runtime();
        new PluginLoader().loadAll(probe, List.of(
            new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
        router.set(null);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Function<String, String> route = router.get();
            String body = route == null ? scripts.poll() : route.apply(request);
            if (body == null) {
                body = answerScript("(script exhausted)");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        probe.close();
    }

    @Test
    void approvalAllowCompletesRoundAndRetiresRuntime() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "hello-note");
        scripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(answerScript("已读"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("read the note");
            await(() -> hub.snapshot(id).pending().size() == 1);
            RelayApprovalPrompt.Pending pending = hub.snapshot(id).pending().get(0);
            assertThat(pending.toolName()).isEqualTo("fs_read");
            assertThat(pending.session().value()).isEqualTo(id);

            control.respondApproval(pending.requestId(), true);
            await(() -> control.completedTurns(id) == 1 && control.openRuntimeCount() == 0);

            assertThat(hub.leaseCount()).isZero();
            assertThat(control.runtimeBoots(id)).isEqualTo(1);
            assertThat(toolResults(id)).anySatisfy(result -> assertThat(result.block().content())
                .contains("hello-note"));
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void sendAfterRetiredRoundColdBootsAgain() throws Exception {
        scripts.add(answerScript("第一轮"));
        scripts.add(answerScript("第二轮"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("第一轮");
            await(() -> control.completedTurns(id) == 1 && control.openRuntimeCount() == 0);
            assertThat(control.runtimeBoots(id)).isEqualTo(1);

            control.send(id, "第二轮");
            await(() -> control.completedTurns(id) == 2 && control.openRuntimeCount() == 0);
            assertThat(control.runtimeBoots(id)).isEqualTo(2);
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void unknownSessionSendAndCancelAreSessionNotFound() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            assertThatThrownBy(() -> control.send("ghost", "hi"))
                .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(-32001));
            assertThatThrownBy(() -> control.cancel("ghost"))
                .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(-32001));
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void deniedApprovalSurfacesHumanGateDenialToTheModel() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "secret");
        scripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(answerScript("已拒绝"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("read");
            await(() -> hub.snapshot(id).pending().size() == 1);
            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), false);
            await(() -> control.completedTurns(id) == 1 && control.openRuntimeCount() == 0);

            assertThat(toolResults(id)).anySatisfy(result -> assertThat(result.block().content())
                .contains("denied by human gate"));
            assertThat(toolResults(id)).allSatisfy(result -> assertThat(result.block().content())
                .doesNotContain("secret"));
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void multiStepSameTurnKeepsSingleLease() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "multi-step");
        scripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(toolCallScript("c2", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(answerScript("读完"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("read twice");
            await(() -> hub.snapshot(id).pending().size() == 1);
            RelayApprovalPrompt.Pending first = hub.snapshot(id).pending().get(0);
            control.respondApproval(first.requestId(), true);

            await(() -> hub.snapshot(id).pending().size() == 1
                && !hub.snapshot(id).pending().get(0).requestId().equals(first.requestId()));
            assertThat(hub.leaseCount(id)).as("同轮多 step 复用同一 REQUEST 信号租约").isEqualTo(1);

            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);
            await(() -> control.completedTurns(id) == 1 && control.openRuntimeCount() == 0);
            assertThat(control.runtimeBoots(id)).isEqualTo(1);
            assertThat(hub.leaseCount()).isZero();
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void concurrentRootSessionsKeepFeedsAndAuditSeparate() throws Exception {
        Path noteA = Files.writeString(workspace.resolve("a.txt"), "note-a");
        Path noteB = Files.writeString(workspace.resolve("b.txt"), "note-b");
        scripts.add(toolCallScript("ca", "fs_read", "{\"path\":\"" + jsonString(noteA.toString()) + "\"}"));
        scripts.add(toolCallScript("cb", "fs_read", "{\"path\":\"" + jsonString(noteB.toString()) + "\"}"));
        scripts.add(answerScript("A 完成"));
        scripts.add(answerScript("B 完成"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String a = control.start("A 读 a");
            await(() -> hub.snapshot(a).pending().size() == 1);
            assertThat(hub.snapshot(a).pending().get(0).session().value()).isEqualTo(a);

            String b = control.start("B 读 b");
            await(() -> hub.snapshot(b).pending().size() == 1);
            assertThat(hub.snapshot(b).pending().get(0).session().value()).isEqualTo(b);
            assertThat(hub.snapshot(a).pending().get(0).requestId())
                .isNotEqualTo(hub.snapshot(b).pending().get(0).requestId());

            control.respondApproval(hub.snapshot(a).pending().get(0).requestId(), true);
            // A 收尾与 runtime 退休是两步：等 A 真退到只剩 B 一个，别把 Windows 的滞后当未退休。
            await(() -> control.completedTurns(a) == 1 && control.openRuntimeCount() == 1);
            assertThat(control.openRuntimeCount()).as("B 的轮仍在途，runtime 未退休").isEqualTo(1);
            assertThat(hub.snapshot(b).pending()).hasSize(1);

            control.respondApproval(hub.snapshot(b).pending().get(0).requestId(), true);
            await(() -> control.completedTurns(b) == 1 && control.openRuntimeCount() == 0);
            assertThat(hub.leaseCount()).isZero();

            assertThat(toolResults(a)).anySatisfy(result ->
                assertThat(result.block().content()).contains("note-a"));
            assertThat(toolResults(a)).allSatisfy(result ->
                assertThat(result.block().content()).doesNotContain("note-b"));
            assertThat(toolResults(b)).anySatisfy(result ->
                assertThat(result.block().content()).contains("note-b"));
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void sendWhileTurnInFlightIsRejected() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "busy");
        scripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(answerScript("好了"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("挂着审批");
            await(() -> hub.snapshot(id).pending().size() == 1);
            assertThatThrownBy(() -> control.send(id, "再来一轮"))
                .isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(-32006));
            assertThat(control.runtimeBoots(id)).as("拒绝不得触发第二次 resume").isEqualTo(1);

            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);
            await(() -> control.completedTurns(id) == 1 && control.openRuntimeCount() == 0);
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void cancelRevokesOnlyThatSessionLeaseAndRetiresRuntime() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "cancel-me");
        scripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("挂着审批");
            await(() -> hub.snapshot(id).pending().size() == 1);

            control.cancel(id);
            await(() -> control.openRuntimeCount() == 0 && hub.leaseCount() == 0);
            assertThat(control.inFlight(id)).isFalse();
            assertThat(hub.snapshot(id).pending()).isEmpty();
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void closeRevokesAllLeasesAndRetiresEveryRuntime() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "close-me");
        scripts.add(toolCallScript("ca", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(toolCallScript("cb", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String a = control.start("A");
            await(() -> hub.snapshot(a).pending().size() == 1);
            String b = control.start("B");
            await(() -> hub.snapshot(b).pending().size() == 1);
            assertThat(control.openRuntimeCount()).isEqualTo(2);

            control.close();
            assertThat(hub.leaseCount()).as("停服撤销整树租约").isZero();
            assertThat(control.openRuntimeCount()).isZero();
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void retainedRoundReusesRuntimeForNextSendThenRetiresAtJobTerminal() throws Exception {
        scripts.add(toolCallScript("j1", "job_start", "{\"command\":\"" + longSleepCommand()
            + "\",\"cwd\":\"" + jsonString(workspace.toString()) + "\",\"timeoutSeconds\":30}"));
        scripts.add(answerScript("任务已起"));
        scripts.add(answerScript("第二轮"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub, List.of(
            new ConfigRowSpec.Replace("jobs-local", Map.of("dataDir", jobsDir.toString()), null)));
        try {
            String id = control.start("起个后台任务");
            await(() -> hub.snapshot(id).pending().size() == 1);
            assertThat(hub.snapshot(id).pending().get(0).toolName()).isEqualTo("job_start");
            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);

            await(() -> control.completedTurns(id) == 1 && control.retained(id));
            assertThat(control.openRuntimeCount()).as("后台任务未终局 → 保留").isEqualTo(1);
            assertThat(control.runtimeBoots(id)).isEqualTo(1);

            control.send(id, "第二轮");
            await(() -> control.completedTurns(id) == 2 && control.retained(id) && !control.inFlight(id));
            assertThat(control.runtimeBoots(id)).as("保留期 send 复用 runtime，不冷启").isEqualTo(1);
            assertThat(control.openRuntimeCount()).isEqualTo(1);

            await(() -> control.openRuntimeCount() == 0 && hub.leaseCount() == 0);
            assertThat(control.runtimeBoots(id)).as("终局退休，不因旧清理再启").isEqualTo(1);
        } finally {
            control.close();
            hub.close();
        }
    }

    @Test
    void delegationChildApprovalStaysInParentFeedAndTreeRetiresAtTerminal() throws Exception {
        Path note = Files.writeString(workspace.resolve("child-note.txt"), "child-note");
        parentScripts.add(toolCallScript("d1", "delegate", "{\"task\":\"TASK-CHILD read the note\","
            + "\"cwd\":\"" + jsonString(workspace.toString()) + "\",\"budgetTokens\":5000}"));
        parentScripts.add(answerScript("已派出"));
        childScripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        childScripts.add(answerScript("child done"));
        router.set(request -> request.contains("PARENT-MARKER") ? parentScripts.poll() : childScripts.poll());

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub, List.of(delegationOverlay()));
        try {
            String id = control.start("PARENT-MARKER 请委派");
            await(() -> hub.snapshot(id).pending().size() == 1);
            assertThat(hub.snapshot(id).pending().get(0).toolName()).isEqualTo("delegate");
            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);

            // 父轮先收尾（whenIdle 已过），子代仍在待审批 → 保留例外
            await(() -> control.completedTurns(id) == 1 && control.retained(id));
            assertThat(control.openRuntimeCount()).isEqualTo(1);
            await(() -> hub.snapshot(id).pending().size() == 1
                && hub.snapshot(id).pending().get(0).toolName().equals("fs_read"));
            RelayApprovalPrompt.Pending child = hub.snapshot(id).pending().get(0);
            assertThat(child.session().value()).as("子代元数据保留自己的会话 id").isNotEqualTo(id);
            assertThat(hub.snapshot(child.session().value()).pending()).as("feed 路由不混用").isEmpty();

            control.respondApproval(child.requestId(), true);
            await(() -> control.openRuntimeCount() == 0 && hub.leaseCount() == 0);
            assertThat(toolResults(child.session().value())).anySatisfy(result ->
                assertThat(result.block().content()).contains("child-note"));
            assertThat(toolResults(id)).anySatisfy(result ->
                assertThat(result.block().content()).contains("已派出"));
        } finally {
            router.set(null);
            control.close();
            hub.close();
        }
    }

    @Test
    void cancelParentDoesNotRevokeChildLease() throws Exception {
        Path note = Files.writeString(workspace.resolve("child-note.txt"), "child-note");
        parentScripts.add(toolCallScript("d1", "delegate", "{\"task\":\"TASK-CHILD read the note\","
            + "\"cwd\":\"" + jsonString(workspace.toString()) + "\",\"budgetTokens\":5000}"));
        parentScripts.add(toolCallScript("p2", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        childScripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        childScripts.add(answerScript("child done"));
        router.set(request -> request.contains("PARENT-MARKER") ? parentScripts.poll() : childScripts.poll());

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub, List.of(delegationOverlay()));
        try {
            String id = control.start("PARENT-MARKER 请委派");
            await(() -> hub.snapshot(id).pending().size() == 1);
            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);

            // 父在途（自己的 fs_read 待批）与子代待批同时在父 feed
            await(() -> hub.snapshot(id).pending().size() == 2);
            List<RelayApprovalPrompt.Pending> pending = hub.snapshot(id).pending();
            RelayApprovalPrompt.Pending child = pending.stream()
                .filter(entry -> !entry.session().value().equals(id)).findFirst().orElseThrow();
            assertThat(pending).anySatisfy(entry -> assertThat(entry.session().value()).isEqualTo(id));

            control.cancel(id);
            await(() -> hub.snapshot(id).pending().size() == 1);
            assertThat(hub.snapshot(id).pending().get(0).session().value())
                .as("父 cancel 不偷撤子租约").isEqualTo(child.session().value());
            assertThat(hub.leaseCount(id)).isEqualTo(1);

            control.respondApproval(child.requestId(), true);
            await(() -> control.openRuntimeCount() == 0 && hub.leaseCount() == 0);
            assertThat(toolResults(child.session().value())).anySatisfy(result ->
                assertThat(result.block().content()).contains("child-note"));
        } finally {
            router.set(null);
            control.close();
            hub.close();
        }
    }

    @Test
    void cancelChildSessionAloneStopsOnlyChildTurn() throws Exception {
        Path note = Files.writeString(workspace.resolve("child-note.txt"), "child-note");
        parentScripts.add(toolCallScript("d1", "delegate", "{\"task\":\"TASK-CHILD read the note\","
            + "\"cwd\":\"" + jsonString(workspace.toString()) + "\",\"budgetTokens\":5000}"));
        parentScripts.add(toolCallScript("p2", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        parentScripts.add(answerScript("parent done"));
        childScripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        childScripts.add(answerScript("child done"));
        router.set(request -> request.contains("PARENT-MARKER") ? parentScripts.poll() : childScripts.poll());

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub, List.of(delegationOverlay()));
        try {
            String id = control.start("PARENT-MARKER 请委派");
            await(() -> hub.snapshot(id).pending().size() == 1);
            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);

            await(() -> hub.snapshot(id).pending().size() == 2);
            RelayApprovalPrompt.Pending child = hub.snapshot(id).pending().stream()
                .filter(entry -> !entry.session().value().equals(id)).findFirst().orElseThrow();

            // 子代单独 cancel（H2）：按 childSessionId 收敛委派，不触父轮
            control.cancel(child.session().value());

            await(() -> hub.snapshot(id).pending().size() == 1 && hub.leaseCount(id) == 1);
            assertThat(hub.snapshot(id).pending().get(0).session().value())
                .as("子代 cancel 只撤子租约").isEqualTo(id);
            assertThat(control.inFlight(id)).isTrue();

            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);
            await(() -> control.openRuntimeCount() == 0 && hub.leaseCount() == 0);
            assertThat(control.completedTurns(id)).isEqualTo(1);
            assertThat(toolResults(id)).anySatisfy(result ->
                assertThat(result.block().content()).contains("child-note"));
        } finally {
            router.set(null);
            control.close();
            hub.close();
        }
    }

    @Test
    void closedHostRefusesLateBootAndReleasesWriterLock() throws Exception {
        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        control.close();

        // 模拟 close 快照早于组合落位的竞态：close 已返回，bootFresh 才完成装配
        Object state = newSessionState("web-late-boot");
        installSessionState(control, state, "web-late-boot", 1);
        Method bootFresh = WebChatControl.class.getDeclaredMethod(
            "bootFresh", state.getClass(), boolean.class);
        bootFresh.setAccessible(true);
        try {
            bootFresh.invoke(control, state, false);
            fail("close 之后不得再落位 runtime");
        } catch (InvocationTargetException e) {
            assertThat(e.getCause()).isInstanceOf(RpcException.class);
            assertThat(((RpcException) e.getCause()).code()).isEqualTo(-32603);
            assertThat(e.getCause()).hasMessageContaining("server is shutting down");
        }
        assertThat(control.openRuntimeCount()).isZero();
        // 装配后未就地收口则写者锁滞留：load 会抛 WriterLockException，而不是「文件不存在」
        assertThatThrownBy(() -> probe.root().require(SessionPersistence.KEY)
            .load(Session.newId("web-late-boot")))
            .isInstanceOf(NoSuchFileException.class);
        hub.close();
    }

    @Test
    void staleGenerationCleanupDoesNotRevokeNewRoundLease() throws Exception {
        Path note = Files.writeString(workspace.resolve("note.txt"), "gen-note");
        scripts.add(answerScript("第一轮"));
        scripts.add(toolCallScript("c1", "fs_read", "{\"path\":\"" + jsonString(note.toString()) + "\"}"));
        scripts.add(answerScript("第二轮"));

        WebApprovalHub hub = new WebApprovalHub(0);
        WebChatControl control = newControl(hub);
        try {
            String id = control.start("第一轮");
            await(() -> control.completedTurns(id) == 1 && control.openRuntimeCount() == 0);
            assertThat(control.runtimeBoots(id)).isEqualTo(1);

            control.send(id, "第二轮");
            await(() -> hub.snapshot(id).pending().size() == 1);
            Object state = sessionStateOf(control, id);

            // 旧代的迟到清理：finishRound 的 st.signal 是所有代共用槽，
            // 代守卫必须先于关信号——否则旧回调关掉新代在途轮的租约
            Method finishRound = WebChatControl.class.getDeclaredMethod(
                "finishRound", state.getClass(), long.class);
            finishRound.setAccessible(true);
            finishRound.invoke(control, state, 1L);

            assertThat(control.inFlight(id)).as("新代仍在途").isTrue();
            assertThat(control.openRuntimeCount()).isEqualTo(1);
            assertThat(hub.snapshot(id).pending()).as("新代租约未被旧清理撤销").hasSize(1);

            control.respondApproval(hub.snapshot(id).pending().get(0).requestId(), true);
            await(() -> control.completedTurns(id) == 2 && control.openRuntimeCount() == 0);
            assertThat(control.runtimeBoots(id)).isEqualTo(2);
        } finally {
            control.close();
            hub.close();
        }
    }

    /** Windows 反斜杠在 JSON 字符串里必须双写；本机无 Windows 也能钉住这一支。 */
    @Test
    void toolArgumentJsonEscapesWindowsSeparators() {
        assertThat(jsonString("C:\\tmp\\note.txt")).isEqualTo("C:\\\\tmp\\\\note.txt");
    }

    // ────────── 夹具 ──────────

    private WebChatControl newControl(WebApprovalHub hub) throws Exception {
        return newControl(hub, List.of());
    }

    private WebChatControl newControl(WebApprovalHub hub, List<ConfigRowSpec> extraOverlays)
            throws Exception {
        List<String> args = new ArrayList<>(List.of(
            "--serve", "--web-write", "--approval=ask",
            "--provider=vendor-x", "--model=x-1",
            "--base-url=http://localhost:" + server.getAddress().getPort(),
            "--api-key=fake-key"));
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(args.toArray(String[]::new));
        List<ConfigRowSpec> overlays = new ArrayList<>(
            HeadlessMain.buildOverlays(options, workspace, sessions));
        overlays.addAll(extraOverlays);
        AppBoot.BootOptions boot = new AppBoot.BootOptions(
            HeadlessMain.resolveProfile(null), overlays, false, Policy.STANDARD);
        return new WebChatControl(boot,
            new AgentOptions(options.provider(), options.model()), hub,
            probe.root().require(SessionPersistence.KEY));
    }

    private static ConfigRowSpec delegationOverlay() {
        return new ConfigRowSpec.Replace("delegation-local",
            Map.of("provider", "vendor-x", "model", "x-1"), null);
    }

    private static String longSleepCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "Start-Sleep -Seconds 10"
            : "sleep 10";
    }

    private List<ToolResultEvent> toolResults(String sessionId) throws Exception {
        return persisted(sessionId).events().stream()
            .filter(event -> event instanceof ToolResultEvent)
            .map(event -> (ToolResultEvent) event)
            .toList();
    }

    private SessionPersistence.Loaded persisted(String sessionId) throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            return rt.root().require(SessionPersistence.KEY).load(Session.newId(sessionId));
        }
    }

    private static Object newSessionState(String id) throws Exception {
        Class<?> type = Class.forName(WebChatControl.class.getName() + "$SessionState");
        var constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id);
    }

    /** 停服竞态用例专用：把状态直接登记进已关闭宿主的会话表（无并发读者）。 */
    private static void installSessionState(WebChatControl control, Object state, String id, long gen)
            throws Exception {
        Field genField = state.getClass().getDeclaredField("gen");
        genField.setAccessible(true);
        genField.setLong(state, gen);
        Field sessionsField = WebChatControl.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> sessions = (Map<String, Object>) sessionsField.get(control);
        sessions.put(id, state);
    }

    private static Object sessionStateOf(WebChatControl control, String id) throws Exception {
        Field sessionsField = WebChatControl.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Map<?, ?> sessions = (Map<?, ?>) sessionsField.get(control);
        return sessions.get(id);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within 20s");
    }

    private static String answerScript(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    }

    /** 工具参数 JSON 内的字符串字面量：Windows 路径含反斜杠，必须转义才成合法 JSON。 */
    private static String jsonString(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toolCallScript(String callId, String name, String arguments) {
        String escaped = arguments.replace("\\", "\\\\").replace("\"", "\\\"");
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + callId
            + "\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + escaped + "\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n"
            + "data: [DONE]\n\n";
    }
}
