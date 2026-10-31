package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LspPeer 会话层行为（夹具 = lsp-fixture 单文件 Content-Length server）：
 * 请求/响应按 id 关联、null result 是合法成功（LSP 语义）、error 成员只失败该调用、
 * server 请求必须应答（含 initialize 前与退出阶段——D2/D4）、畸形帧 = 流级断裂。
 */
class LspPeerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration GENEROUS = Duration.ofSeconds(10);

    @TempDir
    Path temp;

    private LspPeer peer(Process process, List<String> notifications) {
        return new LspPeer(process, "fixture",
            message -> notifications.add(message.path("method").asText()));
    }

    private static ObjectNode params() {
        return JSON.createObjectNode();
    }

    @Test
    void requestReturnsResultMember() throws IOException {
        Process process = Fixtures.launchFixture();
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            JsonNode response = peer.request("initialize", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).path("capabilities").path("textDocumentSync").asInt())
                .isEqualTo(1);
        }
    }

    @Test
    void emptyObjectShutdownResultIsAccepted() throws IOException {
        Process process = Fixtures.launchFixture();
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            JsonNode response = peer.request("shutdown", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).isObject()).isTrue();
        }
    }

    @Test
    void nullResultIsDeliveredAsValidResult() throws IOException {
        Process process = Fixtures.launchFixture("--shutdown-null");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            JsonNode response = peer.request("shutdown", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response)).isNull();
        }
    }

    @Test
    void errorMemberFailsOnlyThatCallAndStreamSurvives() throws IOException {
        Path marker = temp.resolve("error-marker");
        Process process = Fixtures.launchFixture("--error-once", marker.toString());
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            assertThatThrownBy(() -> peer.request("anything", params(), GENEROUS, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code())
                .isEqualTo(LspErrorCode.PROTOCOL);
            JsonNode response = peer.request("anything", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).isObject()).isTrue();
        }
    }

    @Test
    void unmatchedResponseIdIsDroppedAndCallTimesOut() throws IOException {
        Path marker = temp.resolve("wrong-id-marker");
        Process process = Fixtures.launchFixture("--wrong-id-once", marker.toString());
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            assertThatThrownBy(() -> peer.request("anything", params(), Duration.ofMillis(500),
                AbortSignal.never())).isInstanceOf(LspRequestTimeoutException.class);
            assertThat(process.isAlive()).isTrue();
        }
    }

    @Test
    void terminatePreservesTailReadUntilProcessExit() throws IOException {
        Process process = Fixtures.launchFixture("--ignore-term", "--tail-request-delayed", "1200");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            peer.request("shutdown", params(), GENEROUS, AbortSignal.never());
            peer.disableAnswering();
            peer.terminate();
            // TERM 只发信号，不得关闭管道：宽限外请求仍需计为未答。
            Fixtures.awaitTrue("tail request after TERM", () -> peer.unansweredServerRequests() == 1);
            assertThat(process.isAlive()).isTrue();
            assertThat(peer.isBroken()).isFalse();
        }
        Fixtures.awaitProcessExit(process);
    }

    @Test
    void exitPhaseServerRequestIsAnswered() throws IOException, InterruptedException {
        Process process = Fixtures.launchFixture("--tail-request-after-shutdown");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            JsonNode response = peer.request("shutdown", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).isObject()).isTrue();
            Fixtures.awaitProcessExit(process);
            assertThat(process.exitValue()).isZero();
        }
    }

    @Test
    void preInitializeServerRequestIsAnswered() throws IOException, InterruptedException {
        Process process = Fixtures.launchFixture("--create-before-initialize");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            JsonNode response = peer.request("initialize", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).path("capabilities").has("textDocumentSync")).isTrue();
            Fixtures.awaitProcessExit(process);
            assertThat(process.exitValue()).isZero();
        }
    }

    @Test
    void unknownServerRequestGetsMethodNotFoundReply() throws IOException, InterruptedException {
        Process process = Fixtures.launchFixture("--unknown-request");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            peer.notify("initialized", params());
            Fixtures.awaitProcessExit(process);
            assertThat(process.exitValue()).isZero();
        }
    }

    @Test
    void progressNotificationReachesSink() throws IOException {
        Process process = Fixtures.launchFixture("--notify-progress");
        List<String> notifications = new CopyOnWriteArrayList<>();
        try (LspPeer peer = peer(process, notifications)) {
            peer.request("initialize", params(), GENEROUS, AbortSignal.never());
            Fixtures.awaitTrue("$/progress notification", () -> notifications.contains("$/progress"));
        }
    }

    @Test
    void noVersionDiagnosticsReachSink() throws IOException {
        String uri = "file:///project/App.java";
        Process process = Fixtures.launchFixture("--publish-no-version", uri);
        List<String> notifications = new CopyOnWriteArrayList<>();
        try (LspPeer peer = peer(process, notifications)) {
            peer.request("initialize", params(), GENEROUS, AbortSignal.never());
            peer.notify("initialized", params());
            Fixtures.awaitTrue("publishDiagnostics notification",
                () -> notifications.contains("textDocument/publishDiagnostics"));
        }
    }

    @Test
    void receiveSequenceIncreasesWithTraffic() throws IOException {
        Process process = Fixtures.launchFixture("--notify-progress");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            peer.request("initialize", params(), GENEROUS, AbortSignal.never());
            Fixtures.awaitTrue("receive sequence beyond initialize exchange",
                () -> peer.receiveSequence() >= 2);
        }
    }

    @Test
    void eofBreaksSessionWithConnectionError() throws IOException {
        Process process = Fixtures.launchFixture("--exit-after-first-response");
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            JsonNode response = peer.request("initialize", params(), GENEROUS, AbortSignal.never());
            assertThat(LspPeer.resultOf(response).has("capabilities")).isTrue();
            assertThatThrownBy(() -> peer.request("anything", params(), GENEROUS, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code())
                .isEqualTo(LspErrorCode.CONNECTION);
        }
    }

    @Test
    void malformedFrameBreaksSessionWithProtocolError() throws IOException {
        Path marker = temp.resolve("garble-marker");
        Process process = Fixtures.launchFixture("--garble-once", marker.toString());
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            assertThatThrownBy(() -> peer.request("anything", params(), GENEROUS, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code())
                .isEqualTo(LspErrorCode.PROTOCOL);
            assertThat(peer.isBroken()).isTrue();
        }
    }

    @Test
    void cancelSendsCancelRequestAndAborts() throws IOException, InterruptedException {
        Path marker = temp.resolve("delay-marker");
        Path cancelRecord = temp.resolve("cancel-record");
        Process process = Fixtures.launchFixture("--delay-once", marker.toString(), "2000",
            "--record-cancel-to", cancelRecord.toString());
        TestSignal signal = new TestSignal();
        try (LspPeer peer = peer(process, new CopyOnWriteArrayList<>())) {
            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    peer.request("anything", params(), GENEROUS, signal);
                } catch (AbortedException expected) {
                    // 取消是正常路径
                }
            });
            Fixtures.sleep(150);
            signal.cancel();
            Fixtures.awaitTrue("$cancelRequest observed",
                () -> "1".equals(Fixtures.readFileEventually(cancelRecord)));
            waiter.join(2000);
        }
    }

    @Test
    void closeKillsProcessAndFailsPending() throws IOException, InterruptedException {
        Path marker = temp.resolve("close-delay-marker");
        Process process = Fixtures.launchFixture("--delay-once", marker.toString(), "30000");
        LspPeer peer = peer(process, new CopyOnWriteArrayList<>());
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    peer.request("anything", params(), GENEROUS, AbortSignal.never());
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            Fixtures.sleep(150);
            peer.close();
            waiter.join(2000);
            assertThat(failure.get())
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code())
                .isEqualTo(LspErrorCode.CONNECTION);
            Fixtures.awaitProcessExit(process);
            assertThat(peer.isBroken()).isTrue();
        } finally {
            peer.close();
        }
    }

    /** 测试用可取消信号：cancel() 同步执行已注册动作，随后 checkAbort 抛取消。 */
    private static final class TestSignal implements AbortSignal {
        private final List<Runnable> actions = new ArrayList<>();
        private boolean cancelled;

        @Override
        public void checkAbort() {
            if (cancelled) {
                throw new AbortedException("test cancel");
            }
        }

        @Override
        public synchronized void onCancel(Runnable action) {
            if (cancelled) {
                action.run();
            } else {
                actions.add(action);
            }
        }

        synchronized void cancel() {
            cancelled = true;
            actions.forEach(Runnable::run);
            actions.clear();
        }
    }
}
