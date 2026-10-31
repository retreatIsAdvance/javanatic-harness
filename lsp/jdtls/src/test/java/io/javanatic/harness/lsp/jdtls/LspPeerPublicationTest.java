package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原有发布/异常/EOF 红测，独立保留；不属于本轮背压与回收聚焦面。 */
class LspPeerPublicationTest {
    private static final Duration SHORT = Duration.ofMillis(300);
    private static final Duration BOUND = Duration.ofSeconds(3);

    @TempDir
    Path temp;

    @Test
    void captureAndSequenceCannotObserveHalfPublishedNotification() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> candidate = new AtomicReference<>("old");
        try (Harness h = launch(message -> {
            if ("textDocument/publishDiagnostics".equals(message.path("method").asText())) {
                entered.countDown();
                await(release);
                candidate.set("new");
            }
        })) {
            try {
                h.peer.notify("publish", null);
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                CountDownLatch started = new CountDownLatch(2);
                Running seq = run(() -> {
                    started.countDown();
                    return h.peer.receiveSequence();
                });
                Running captured = run(() -> {
                    started.countDown();
                    return capture(h.peer, n -> n + ":" + candidate.get());
                });
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                seq.thread.join(100);
                captured.thread.join(100);
                assertThat(seq.thread.isAlive()).as("sequence must wait for callback publication").isTrue();
                assertThat(captured.thread.isAlive()).as("capture must wait for callback publication").isTrue();
                release.countDown();
                assertThat(seq.resultWithin(BOUND)).isEqualTo(2L);
                assertThat(captured.resultWithin(BOUND)).isEqualTo("2:new");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void callbackProtocolFailureIsNotSwallowedOrReclassified() throws Exception {
        LspException original = new LspException(LspErrorCode.PROTOCOL, "missing diagnostics array");
        try (Harness h = launch(message -> {
            if ("textDocument/publishDiagnostics".equals(message.path("method").asText())) {
                throw original;
            }
        })) {
            assertThat(run(() -> h.peer.request("publish", null, BOUND, AbortSignal.never()))
                .resultWithin(BOUND)).isSameAs(original);
            assertThatThrownBy(() -> invoke(h.peer, "checkHealthy")).isSameAs(original);
            assertThatThrownBy(() -> h.peer.request("later", null, BOUND, AbortSignal.never()))
                .isSameAs(original);
            assertThatThrownBy(() -> capture(h.peer, n -> n)).isSameAs(original);
            assertThat(h.peer.awaitStdoutEof(SHORT)).isFalse();
            h.peer.close();
            assertThatThrownBy(() -> invoke(h.peer, "checkHealthy")).isSameAs(original);
        }
    }

    @Test
    void callbackRuntimeFailureBecomesProtocolAndReaderTerminationIsNotEof() throws Exception {
        try (Harness h = launch(message -> {
            if ("textDocument/publishDiagnostics".equals(message.path("method").asText())) {
                throw new IllegalArgumentException("bad callback");
            }
        })) {
            Object failure = run(() -> h.peer.request("publish", null, BOUND, AbortSignal.never()))
                .resultWithin(BOUND);
            assertProtocol(failure);
            assertThat(((LspException) failure).getCause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(h.peer.awaitStdoutEof(SHORT)).isFalse();
        }
    }

    @Test
    void malformedFrameFailureIsRetainedAndIsNotStdoutEof() throws Exception {
        try (Harness h = launch(message -> { })) {
            Object failure = run(() -> h.peer.request("malformed", null, BOUND, AbortSignal.never()))
                .resultWithin(BOUND);
            assertProtocol(failure);
            assertThat(h.peer.awaitStdoutEof(SHORT)).as("reader failure is not EOF").isFalse();
            assertThatThrownBy(() -> invoke(h.peer, "checkHealthy")).isSameAs(failure);
        }
    }

    @Test
    void partialFrameEofIsReadFailureNotCleanEof() throws Exception {
        try (Harness h = launch(message -> { })) {
            Object failure = run(() -> h.peer.request("partial", null, BOUND, AbortSignal.never()))
                .resultWithin(BOUND);
            assertThat(failure).isInstanceOf(LspException.class);
            assertThat(((LspException) failure).code()).isEqualTo(LspErrorCode.CONNECTION);
            assertThat(h.peer.awaitStdoutEof(SHORT)).isFalse();
        }
    }

    private Harness launch(Consumer<ObjectNode> callback) throws Exception {
        Path classes = Path.of(PeerBackpressureServer.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        Process process = new ProcessBuilder(Fixtures.javaBin(), "-cp", classes.toString(),
            PeerBackpressureServer.class.getName(), "echo", temp.toString()).start();
        CountDownLatch ready = new CountDownLatch(1);
        LspPeer peer = new LspPeer(process, "publication-" + process.pid(), message -> {
            if ("ready".equals(message.path("method").asText())) {
                ready.countDown();
            } else {
                callback.accept(message);
            }
        });
        Harness harness = new Harness(process, peer);
        if (!ready.await(5, TimeUnit.SECONDS)) {
            harness.close();
            throw new AssertionError("fixture did not become ready");
        }
        return harness;
    }

    private record Harness(Process process, LspPeer peer) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.toHandle().destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("owned fixture reaped").isTrue();
            assertThat(run(() -> {
                peer.close();
                return "closed";
            }).resultWithin(BOUND)).isEqualTo("closed");
        }
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("callback release not received");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Object invoke(LspPeer peer, String method) {
        return invoke(peer, method, new Class<?>[0]);
    }

    private static Object invoke(LspPeer peer, String method, Class<?>[] types, Object... args) {
        try {
            return LspPeer.class.getDeclaredMethod(method, types).invoke(peer, args);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("missing production primitive: " + method, e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new AssertionError(e.getCause());
        }
    }

    private static Object capture(LspPeer peer, LongFunction<?> capture) {
        return invoke(peer, "capture", new Class<?>[] {LongFunction.class}, capture);
    }
}
