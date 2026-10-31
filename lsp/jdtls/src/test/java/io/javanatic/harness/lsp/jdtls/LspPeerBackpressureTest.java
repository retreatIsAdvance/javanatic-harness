package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真管道背压与发布边界：失败也只回收本测试持有的子进程与线程。 */
class LspPeerBackpressureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SHORT = Duration.ofMillis(300);
    private static final Duration LONG = Duration.ofSeconds(30);
    private static final Duration BOUND = Duration.ofSeconds(3);
    private static final String LARGE_TEXT = "x".repeat(3 * 1024 * 1024);

    @TempDir
    Path temp;

    @Test
    void requestDeadlineIncludesBlockedPipeWrite() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), SHORT, AbortSignal.never()));
            h.awaitWrite();
            assertThat(request.resultWithin(BOUND)).isInstanceOf(LspRequestTimeoutException.class);
            assertThat(h.process.isAlive()).isTrue();
            assertThat(h.peer.isBroken()).isFalse();
            assertThat(h.peer.pendingCount()).isEqualTo(0);
        }
    }

    @Test
    void pollingCancellationIncludesBlockedPipeWriteWithoutKillingProject() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AbortSignal signal = () -> {
            if (cancelled.get()) {
                throw new AbortedException("polling cancellation");
            }
        };
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, signal));
            h.awaitWrite();
            cancelled.set(true);
            assertThat(request.resultWithin(BOUND)).isInstanceOf(AbortedException.class);
            assertThat(h.process.isAlive()).isTrue();
            assertThat(h.peer.isBroken()).isFalse();
            assertThat(h.peer.pendingCount()).isEqualTo(0);
        }
    }

    @Test
    void listenerCancellationDoesNotBlockInCancelNotification() throws Exception {
        ListeningSignal signal = new ListeningSignal();
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, signal));
            h.awaitWrite();
            assertThat(run(() -> {
                signal.cancel();
                return "cancelled";
            }).resultWithin(BOUND))
                .isEqualTo("cancelled");
            assertThat(request.resultWithin(BOUND)).isInstanceOf(AbortedException.class);
            assertThat(h.process.isAlive()).isTrue();
        }
    }

    @Test
    void closeKillsBeforeWaitingForOutputLockAndReapsIoThreads() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            Running close = run(() -> {
                h.peer.close();
                return "closed";
            });
            assertThat(close.resultWithin(BOUND)).isEqualTo("closed");
            assertThat(request.resultWithin(BOUND)).isInstanceOf(LspException.class);
            assertThat(h.process.isAlive()).isFalse();
            assertThat(h.peer.pendingCount()).isEqualTo(0);
            assertThat(h.peer.ioThreadsStopped()).isEqualTo(true);
            h.peer.close();
        }
    }

    @Test
    void readerEnqueuesRepliesWithoutBlockingAndCountsOnlyFlushedReplies() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (Harness h = launch("blocked", message -> {
            if ("after-server-request".equals(message.path("method").asText())) {
                received.countDown();
            }
        })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            h.command("traffic");
            assertThat(received.await(3, TimeUnit.SECONDS)).as("reader must pass blocked reply").isTrue();
            assertThat(h.peer.unansweredServerRequests()).isEqualTo(1);
            h.command("resume");
            assertThat(request.resultWithin(BOUND)).isInstanceOf(ObjectNode.class);
            Fixtures.awaitTrue("reply flushed", () -> h.peer.unansweredServerRequests() == 0);
        }
    }

    @Test
    void stdoutEofStillFlushesAlreadyParsedRepliesAfterBackpressureClears() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            h.command("half-close");
            assertThat(h.peer.awaitStdoutEof(BOUND)).isTrue();
            Object failure = request.resultWithin(BOUND);
            assertThat(failure).isInstanceOf(LspException.class);
            assertThat(((LspException) failure).code()).isEqualTo(LspErrorCode.CONNECTION);
            assertThat(h.peer.unansweredServerRequests()).isEqualTo(1);
            assertThat(h.process.isAlive()).as("stdout EOF is not process exit").isTrue();
            h.command("resume");
            assertThat(h.peer.awaitExit(BOUND)).as("server must receive its queued reply").isTrue();
            assertThat(Files.readString(temp.resolve("reply-received")))
                .contains("\"id\":\"server\"", "\"result\":null");
            assertThat(h.peer.unansweredServerRequests()).isZero();
        }
    }

    @Test
    void replyQueueOverflowBreaksLoudlyInsteadOfDroppingMessages() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            h.command("flood");
            assertProtocol(request.resultWithin(BOUND));
            assertThat(h.peer.unansweredServerRequests()).isPositive();
            assertThat(h.peer.isBroken()).isTrue();
        }
    }

    @Test
    void notificationTimeoutAndCancellationWaitForFlushAndRemainLocal() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (Harness h = launch("blocked", message -> { })) {
            Running first = run(() -> timedNotify(h.peer, large(), SHORT, AbortSignal.never()));
            h.awaitWrite();
            assertThat(first.resultWithin(BOUND)).isInstanceOf(LspRequestTimeoutException.class);
            Running queued = run(() -> timedNotify(h.peer, null, LONG, () -> {
                if (cancelled.get()) {
                    throw new AbortedException("notify cancellation");
                }
            }));
            cancelled.set(true);
            assertThat(queued.resultWithin(BOUND)).isInstanceOf(AbortedException.class);
            assertThat(h.peer.isBroken()).isFalse();
            assertThat(h.process.isAlive()).isTrue();
        }
    }

    @Test
    void queuedRequestUsesEntryBudgetRatherThanASecondWriteBudget() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running first = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            Running queued = run(() -> h.peer.request("queued", null, SHORT, AbortSignal.never()));
            assertThat(queued.resultWithin(BOUND)).isInstanceOf(LspRequestTimeoutException.class);
            assertThat(first.thread.isAlive()).isTrue();
            h.peer.close();
            assertThat(first.resultWithin(BOUND)).isInstanceOf(LspException.class);
        }
    }

    @Test
    void callerQueueOverflowIsTypedAndFailsEveryWaiterWithoutKillingProcess() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            List<Running> notifications = new ArrayList<>();
            for (int i = 0; i < 96; i++) {
                notifications.add(run(() -> timedNotify(h.peer, null, LONG, AbortSignal.never())));
            }
            assertProtocol(request.resultWithin(BOUND));
            List<LspErrorCode> codes = new ArrayList<>();
            for (Running notification : notifications) {
                Object failure = notification.resultWithin(BOUND);
                assertThat(failure).isInstanceOf(LspException.class);
                codes.add(((LspException) failure).code());
            }
            assertThat(codes).contains(LspErrorCode.PROTOCOL);
            assertThat(h.peer.pendingCount()).isZero();
            assertThat(h.peer.isBroken()).isTrue();
            assertThat(h.process.isAlive()).isTrue();
        }
    }

    @Test
    void finishReportsUnstoppedThreadsThenConvergesAfterKill() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running request = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            Running finish = run(h.peer::finish);
            assertThat(request.resultWithin(Duration.ofSeconds(1))).isInstanceOf(LspException.class);
            assertThat(finish.resultWithin(BOUND)).isEqualTo(false);
            assertThat(h.peer.pendingCount()).isZero();
            assertThat(h.peer.ioThreadsStopped()).isFalse();
            assertThat(h.process.isAlive()).isTrue();
            h.peer.killTree();
            assertThat(h.peer.awaitExit(BOUND)).isTrue();
            assertThat(run(h.peer::finish).resultWithin(BOUND)).isEqualTo(true);
            assertThat(h.peer.ioThreadsStopped()).isTrue();
            assertThat(h.peer.exitCode()).isPresent();
        }
    }

    @Test
    void legacyNotificationHasFiniteDefaultFlushDeadline() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running notification = run(() -> {
                h.peer.notify("notice", large());
                return "flushed";
            });
            h.awaitWrite();
            assertThat(notification.resultWithin(BOUND)).isInstanceOf(LspRequestTimeoutException.class);
            assertThat(h.peer.isBroken()).isFalse();
            assertThat(h.process.isAlive()).isTrue();
        }
    }

    @Test
    void notificationReturnsOnlyAfterFlushSucceeds() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running notification = run(() -> timedNotify(h.peer, large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            notification.thread.join(100);
            assertThat(notification.thread.isAlive()).as("enqueue is not flush").isTrue();
            h.command("resume");
            assertThat(notification.resultWithin(BOUND)).isEqualTo("flushed");
            assertThat(h.peer.isBroken()).isFalse();
        }
    }

    @Test
    void beginCloseFailsInFlightNotificationWithoutWaitingForFlush() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running notification = run(() -> timedNotify(h.peer, large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            h.peer.beginClose();
            assertThat(notification.resultWithin(BOUND)).isInstanceOf(LspException.class);
            assertThat(h.process.isAlive()).isTrue();
        }
    }

    @Test
    void beginCloseRejectsOrdinaryWorkButPermitsShutdownAndExit() throws Exception {
        try (Harness h = launch("blocked", message -> { })) {
            Running first = run(() -> h.peer.request("large", large(), LONG, AbortSignal.never()));
            h.awaitWrite();
            h.peer.beginClose();
            assertThat(first.resultWithin(BOUND)).isInstanceOf(LspException.class);
            assertThat(h.peer.pendingCount()).isEqualTo(0);
            assertThatThrownBy(() -> h.peer.request("ordinary", null, LONG, AbortSignal.never()))
                .isInstanceOf(LspException.class);
            assertThatThrownBy(() -> h.peer.notify("ordinary", null)).isInstanceOf(LspException.class);
            h.command("resume");
            assertThat(LspPeer.resultOf(h.peer.request("shutdown", null, BOUND, AbortSignal.never())))
                .isNull();
            h.peer.notify("exit", null);
            assertThat(h.peer.awaitStdoutEof(BOUND)).isTrue();
            assertThat(h.peer.awaitExit(BOUND)).isTrue();
        }
    }

    private Harness launch(String mode, Consumer<ObjectNode> callback) throws Exception {
        Path classes = Path.of(PeerBackpressureServer.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        Process process = new ProcessBuilder(Fixtures.javaBin(), "-cp", classes.toString(),
            PeerBackpressureServer.class.getName(), mode, temp.toString()).start();
        CountDownLatch ready = new CountDownLatch(1);
        LspPeer peer = new LspPeer(process, "backpressure-" + process.pid(), message -> {
            if ("ready".equals(message.path("method").asText())) {
                ready.countDown();
            } else {
                callback.accept(message);
            }
        });
        Harness harness = new Harness(process, peer, temp);
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("fixture ready").isTrue();
            assertThat(LspPeer.resultOf(peer.request("initialize", null, BOUND, AbortSignal.never()))
                .has("capabilities")).isTrue();
            return harness;
        } catch (Exception | AssertionError failure) {
            harness.close();
            throw failure;
        }
    }

    private record Harness(Process process, LspPeer peer, Path control) implements AutoCloseable {
        void awaitWrite() {
            Fixtures.awaitTrue("real stdin write started", () -> Files.exists(control.resolve("write-started")));
        }

        void command(String command) throws Exception {
            Files.writeString(control.resolve(command), "go");
        }

        @Override
        public void close() throws Exception {
            // 先独立杀自有 PID，即使旧实现 close 卡输出锁，红测也不会遗留子进程。
            process.toHandle().destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("owned fixture reaped").isTrue();
            assertThat(run(() -> {
                peer.close();
                return "closed";
            }).resultWithin(BOUND)).isEqualTo("closed");
            assertThat(peer.pendingCount()).isZero();
            assertThat(peer.ioThreadsStopped()).isTrue();
            System.out.println("owned pid=" + process.pid() + " exit=" + process.exitValue()
                + " pending=" + peer.pendingCount() + " ioThreadsStopped=" + peer.ioThreadsStopped());
        }
    }

    private static ObjectNode large() {
        return JSON.createObjectNode().put("text", LARGE_TEXT);
    }

    private static Running run(Supplier<Object> action) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                result.complete(action.get());
            } catch (RuntimeException | AssertionError failure) {
                result.complete(failure);
            }
        });
        return new Running(thread, result);
    }

    private record Running(Thread thread, CompletableFuture<Object> result) {
        Object resultWithin(Duration timeout) throws InterruptedException {
            thread.join(timeout.toMillis());
            assertThat(thread.isAlive()).as("operation must finish within " + timeout).isFalse();
            return result.join();
        }
    }

    private static void assertProtocol(Object failure) {
        assertThat(failure).isInstanceOf(LspException.class);
        assertThat(((LspException) failure).code()).isEqualTo(LspErrorCode.PROTOCOL);
    }

    private static Object timedNotify(LspPeer peer, ObjectNode params, Duration timeout, AbortSignal signal) {
        peer.notify("notice", params, timeout, signal);
        return "flushed";
    }

    private static final class ListeningSignal implements AbortSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void checkAbort() {
            if (cancelled.get()) {
                throw new AbortedException("listener cancellation");
            }
        }

        @Override
        public void onCancel(Runnable listener) {
            listeners.add(listener);
            if (cancelled.get()) {
                listener.run();
            }
        }

        void cancel() {
            cancelled.set(true);
            listeners.forEach(Runnable::run);
        }
    }
}
