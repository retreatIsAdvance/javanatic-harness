package io.javanatic.harness.lsp.jdtls;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 调用入口的一个总预算，覆盖排队、初始化和真实窗口，不靠伪造 elapsedWait。 */
class JdtlsProviderBudgetTest {

    @TempDir
    Path temp;

    @Test
    void queuedObservationConsumesItsOwnEntryBudget() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path opened = temp.resolve("opened");
        try (JdtlsProvider provider = provider("--write-on-open", opened.toString())) {
            CompletableFuture<LspResult<LspDiagnostic>> first = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    first.complete(provider.diagnostics(query(document, 6000, 2000), AbortSignal.never()));
                } catch (RuntimeException e) {
                    first.completeExceptionally(e);
                }
            });
            try {
                Fixtures.awaitTrue("first observation synchronized", () -> Files.exists(opened));
                long start = System.nanoTime();
                var result = provider.diagnostics(query(document, 200, 100), AbortSignal.never());
                assertThat(result).isInstanceOf(LspResult.TimedOut.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            } finally {
                first.get(8, TimeUnit.SECONDS);
                worker.join(Duration.ofSeconds(1));
            }
        }
    }

    @Test
    void cancellationDuringInitializationDoesNotWaitForHandshake() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path wire = temp.resolve("wire");
        try (JdtlsProvider provider = provider("--delay-init", "1500", "--record-wire-to", wire.toString())) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AbortSignal signal = () -> {
                if (cancelled.get()) {
                    throw new AbortedException("initialization cancelled");
                }
            };
            CompletableFuture<Object> result = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    result.complete(provider.diagnostics(query(document, 6000, 100), signal));
                } catch (RuntimeException e) {
                    result.complete(e);
                }
            });
            try {
                Fixtures.awaitTrue("initialize on wire", () -> Files.exists(wire));
                cancelled.set(true);
                assertThat(result.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(AbortedException.class);
            } finally {
                worker.join(Duration.ofSeconds(5));
            }
            assertThat(provider.diagnostics(query(document, 6000, 100), AbortSignal.never()))
                .isInstanceOf(LspResult.Observed.class);
        }
    }

    @Test
    void locationMustWaitForTheRequestedObservationWindow() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        try (JdtlsProvider provider = provider()) {
            provider.diagnostics(query(document, 6000, 100), AbortSignal.never());
            long start = System.nanoTime();
            var result = provider.definition(new LspPositionQuery(query(document, 6000, 600),
                new LspPosition(0, 0)), AbortSignal.never());
            assertThat(result).isInstanceOf(LspResult.Observed.class);
            var observation = ((LspResult.Observed<?>) result).observation();
            assertThat(observation.requiredWait()).isEqualTo(Duration.ofMillis(600));
            assertThat(observation.elapsedWait()).isGreaterThanOrEqualTo(Duration.ofMillis(600));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(600));
        }
    }

    @Test
    void closeFailsInflightRequestBeforeWaitingForShutdown() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path wire = temp.resolve("wire");
        JdtlsProvider provider = provider("--definition", "ignore", "--hang-on-shutdown", "--ignore-term",
            "--record-wire-to", wire.toString());
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                result.complete(provider.definition(new LspPositionQuery(query(document, 10000, 100),
                    new LspPosition(0, 0)), AbortSignal.never()));
            } catch (RuntimeException e) {
                result.complete(e);
            }
        });
        CompletableFuture<Object> closing = new CompletableFuture<>();
        try {
            Fixtures.awaitTrue("definition on wire", () -> contains(wire, "textDocument/definition"));
            Thread.ofVirtual().start(() -> {
                try {
                    provider.close();
                    closing.complete("closed");
                } catch (RuntimeException e) {
                    closing.complete(e);
                }
            });
            Fixtures.awaitTrue("shutdown on wire", () -> contains(wire, "shutdown"));
            Object stopped = result.get(500, TimeUnit.MILLISECONDS);
            assertThat(stopped).isEqualTo(new LspResult.NotReady<>(LspResult.NotReadyReason.CLOSED));
            Object failure = closing.get(5, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(LspException.class);
            assertThat(((LspException) failure).code()).isEqualTo(LspErrorCode.CLOSE_FAILED);
            assertThat(provider.exitCode()).hasValue(Fixtures.signalExit(9)); // POSIX SIGKILL / Windows TerminateProcess
        } finally {
            // 即使快速收口断言失败，也必须等自有关闭升级结束，不能泄漏夹具。
            if (!closing.isDone()) {
                closing.get(6, TimeUnit.SECONDS);
            }
            provider.close();
            worker.join(Duration.ofSeconds(1));
            assertThat(ProcessHandle.of(provider.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test
    void concurrentCloseDoesNotWaitForTheLongObservationWindow() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path opened = temp.resolve("opened");
        JdtlsProvider provider = provider("--write-on-open", opened.toString(), "--hang-on-exit", "--ignore-term");
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                result.complete(provider.diagnostics(query(document, 15000, 10000), AbortSignal.never()));
            } catch (RuntimeException e) {
                result.complete(e);
            }
        });
        try {
            Fixtures.awaitTrue("long window started", () -> Files.exists(opened));
            long start = System.nanoTime();
            provider.close();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(result.get(1, TimeUnit.SECONDS))
                .isEqualTo(new LspResult.NotReady<>(LspResult.NotReadyReason.CLOSED));
            assertThat(provider.exitCode()).hasValue(Fixtures.signalExit(9)); // POSIX SIGKILL / Windows TerminateProcess
            assertThat(provider.pendingCount()).isZero();
            assertThat(provider.ioThreadsStopped()).isTrue();
        } finally {
            provider.close();
            worker.join(Duration.ofSeconds(1));
        }
    }

    @Test
    void closingDuringInitializationKeepsShutdownAndExitRatherThanRacingKill() throws Exception {
        Path wire = temp.resolve("wire");
        try (JdtlsProvider provider = provider("--delay-init", "700", "--record-wire-to", wire.toString())) {
            Fixtures.awaitTrue("initialize on wire", () -> contains(wire, "initialize"));
            provider.close();
            assertThat(provider.exitCode()).hasValue(0);
            assertThat(contains(wire, "shutdown")).isTrue();
            assertThat(contains(wire, "exit")).isTrue();
            assertThat(provider.pendingCount()).isZero();
            assertThat(provider.ioThreadsStopped()).isTrue();
        }
    }

    @Test
    void queuedObservationCanBeCancelledWithoutWaitingForTheOwner() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path opened = temp.resolve("opened");
        try (JdtlsProvider provider = provider("--write-on-open", opened.toString())) {
            CompletableFuture<Object> first = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    first.complete(provider.diagnostics(query(document, 6000, 2000), AbortSignal.never()));
                } catch (RuntimeException e) {
                    first.complete(e);
                }
            });
            AtomicBoolean cancelled = new AtomicBoolean();
            CountDownLatch waiting = new CountDownLatch(3);
            CompletableFuture<Object> queued = new CompletableFuture<>();
            try {
                Fixtures.awaitTrue("owner acquired observation zone", () -> Files.exists(opened));
                Thread.ofVirtual().start(() -> {
                    try {
                        queued.complete(provider.diagnostics(query(document, 6000, 100), () -> {
                            waiting.countDown();
                            if (cancelled.get()) {
                                throw new AbortedException("queued observation cancelled");
                            }
                        }));
                    } catch (RuntimeException e) {
                        queued.complete(e);
                    }
                });
                assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);
                assertThat(queued.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(AbortedException.class);
            } finally {
                first.get(8, TimeUnit.SECONDS);
            }
        }
    }

    /** stdout 半关闭（EOF）后 close：已解析应答仍被 flush（对端收到）、进程被收割；
     *  但会话先于 shutdown 握手终结 → CLOSE_FAILED（设计 §4.2 步 8），且不得误报未答请求。 */
    @Test
    void closeAfterStdoutEofFlushesRepliesThenReportsUnansweredShutdown() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path control = Files.createDirectories(temp.resolve("control"));
        try (JdtlsProvider provider = backpressureProvider("provider-blocked", control,
            Duration.ofMillis(100))) {
            CompletableFuture<Object> result = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    result.complete(provider.diagnostics(query(document, 15000, 10000), AbortSignal.never()));
                } catch (RuntimeException e) {
                    result.complete(e);
                }
            });
            try {
                Fixtures.awaitTrue("document sync reached fixture",
                    () -> Files.exists(control.resolve("write-started")));
                Files.writeString(control.resolve("half-close"), "go");
                Object failure = result.get(3, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(LspException.class);
                assertThat(((LspException) failure).code()).isEqualTo(LspErrorCode.CONNECTION);
                Files.writeString(control.resolve("resume"), "go");
                Fixtures.awaitTrue("fixture received its reply and exited", () -> provider.exitCode().isPresent());
                assertThat(provider.exitCode()).hasValue(0);
                assertThat(Files.readString(control.resolve("reply-received")))
                    .contains("\"id\":\"server\"").contains("\"result\":null");
                long start = System.nanoTime();
                assertThatThrownBy(provider::close).isInstanceOf(LspException.class)
                    .satisfies(f -> assertThat(((LspException) f).code()).isEqualTo(LspErrorCode.CLOSE_FAILED))
                    .hasMessageContaining("shutdown unanswered")
                    .hasMessageNotContaining("unanswered server requests");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                assertThat(provider.pendingCount()).isZero();
                assertThat(provider.ioThreadsStopped()).isTrue();
            } finally {
                worker.join(Duration.ofSeconds(1));
            }
        }
    }

    /** 应答无法在排水宽限内 flush：close 必须先给满宽限再失败，不伪称全答（设计 §3.3/§4.2 步 3）。 */
    @Test
    void closeFailsLoudlyWhenParsedReplyCannotFlushWithinDrainGrace() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"),
            "class App {}\n// " + "x".repeat(3 * 1024 * 1024) + "\n");
        Path control = Files.createDirectories(temp.resolve("control"));
        JdtlsProvider provider = backpressureProvider("provider-blocked", control, Duration.ofMillis(500));
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                result.complete(provider.diagnostics(query(document, 15000, 10000), AbortSignal.never()));
            } catch (RuntimeException e) {
                result.complete(e);
            }
        });
        try {
            Fixtures.awaitTrue("document sync reached fixture",
                () -> Files.exists(control.resolve("write-started")));
            Files.writeString(control.resolve("half-close"), "go");
            Object failure = result.get(3, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(LspException.class);
            assertThat(((LspException) failure).code()).isEqualTo(LspErrorCode.CONNECTION);
            long start = System.nanoTime();
            assertThatThrownBy(provider::close).isInstanceOf(LspException.class)
                .satisfies(f -> assertThat(((LspException) f).code()).isEqualTo(LspErrorCode.CLOSE_FAILED))
                .hasMessageContaining("unanswered server requests=1");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("drain grace must be honored before giving up")
                .isGreaterThanOrEqualTo(Duration.ofMillis(400));
            assertThat(ProcessHandle.of(provider.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(provider.exitCode()).hasValue(Fixtures.signalExit(15)); // POSIX SIGTERM / Windows TerminateProcess
        } finally {
            worker.join(Duration.ofSeconds(1));
            provider.close();
        }
    }

    /** R3-1 超时反例：didChange(B/v2) 背压超时后 openedDocuments 必须已承诺 B/v2（入队即记账）；
     *  磁盘回退 A 后下一观察必须发 didChange(A/v3)，不得因 hash 相等跳过（旧缺陷：配 A 锚返回而 LS 仍为 B）。 */
    @Test
    void syncTimeoutStillCommitsThenRevertSendsDidChange() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), APP_A);
        Path control = Files.createDirectories(temp.resolve("control"));
        try (JdtlsProvider provider = backpressureProvider("sync-blocked", control, Duration.ofMillis(100))) {
            LspResult<LspDiagnostic> first = provider.diagnostics(query(document, 15000, 100), AbortSignal.never());
            assertThat(first).isInstanceOf(LspResult.Observed.class);
            Fixtures.awaitTrue("first sync frame recorded", () -> contains(control.resolve("sync-log"), "didOpen"));
            Files.writeString(document, APP_B);
            LspResult<LspDiagnostic> second = provider.diagnostics(query(document, 2000, 100), AbortSignal.never());
            assertThat(second).isInstanceOf(LspResult.TimedOut.class);
            Fixtures.awaitTrue("second sync frame stalled at fixture",
                () -> Files.exists(control.resolve("second-started")));
            Files.writeString(document, APP_A);
            Files.writeString(control.resolve("resume"), "go");
            LspResult<LspDiagnostic> third = provider.diagnostics(query(document, 15000, 100), AbortSignal.never());
            assertThat(third).isInstanceOf(LspResult.Observed.class);
            Fixtures.awaitTrue("reverted text flushed as didChange v3",
                () -> anyLine(control.resolve("sync-log"), "\"method\":\"textDocument/didChange\"",
                    "\"version\":3", "REVERTED"));
        }
    }

    /** R3-1 取消反例：同步等待被取消（不超时）时已入队帧不得撤销；记账在入队点，磁盘回退后同样发 didChange(A/v3)。 */
    @Test
    void syncCancelledStillCommitsThenRevertSendsDidChange() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), APP_A);
        Path control = Files.createDirectories(temp.resolve("control"));
        try (JdtlsProvider provider = backpressureProvider("sync-blocked", control, Duration.ofMillis(100))) {
            LspResult<LspDiagnostic> first = provider.diagnostics(query(document, 15000, 100), AbortSignal.never());
            assertThat(first).isInstanceOf(LspResult.Observed.class);
            Fixtures.awaitTrue("first sync frame recorded", () -> contains(control.resolve("sync-log"), "didOpen"));
            Files.writeString(document, APP_B);
            AtomicBoolean cancelled = new AtomicBoolean();
            AbortSignal signal = () -> {
                if (cancelled.get()) {
                    throw new AbortedException("sync cancelled");
                }
            };
            CompletableFuture<Object> second = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    second.complete(provider.diagnostics(query(document, 15000, 100), signal));
                } catch (RuntimeException e) {
                    second.complete(e);
                }
            });
            Fixtures.awaitTrue("second sync frame stalled at fixture",
                () -> Files.exists(control.resolve("second-started")));
            cancelled.set(true);
            assertThat(second.get(3, TimeUnit.SECONDS)).isInstanceOf(AbortedException.class);
            Files.writeString(document, APP_A);
            Files.writeString(control.resolve("resume"), "go");
            LspResult<LspDiagnostic> third = provider.diagnostics(query(document, 15000, 100), AbortSignal.never());
            assertThat(third).isInstanceOf(LspResult.Observed.class);
            Fixtures.awaitTrue("reverted text flushed as didChange v3",
                () -> anyLine(control.resolve("sync-log"), "\"method\":\"textDocument/didChange\"",
                    "\"version\":3", "REVERTED"));
            worker.join(Duration.ofSeconds(1));
        }
    }

    /** 真背压对端（PeerBackpressureServer，独立 JVM，-cp 类名启动；不用 LspFixtureServer 单文件源）。 */
    private JdtlsProvider backpressureProvider(String mode, Path control, Duration drainGrace) throws Exception {
        Path classes = Path.of(PeerBackpressureServer.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        List<String> argv = List.of(Fixtures.javaBin(), "-cp", classes.toString(),
            PeerBackpressureServer.class.getName(), mode, control.toString());
        return new JdtlsProvider(temp, argv, null, Duration.ofSeconds(8),
            new JdtlsProvider.CloseBudget(Duration.ofSeconds(2), drainGrace,
                Duration.ofMillis(100), Duration.ofSeconds(1)));
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.exists(file) && Files.readString(file).contains(text);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** 同一行同时含全部片段（R3-1 判别：didChange + version 3 + 回退标记，三者不可分散在不同帧行）。 */
    private static boolean anyLine(Path file, String... fragments) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            return Files.readAllLines(file).stream().anyMatch(line -> {
                for (String fragment : fragments) {
                    if (!line.contains(fragment)) {
                        return false;
                    }
                }
                return true;
            });
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final String APP_A = "class App {\n    // REVERTED\n}\n";

    private static final String APP_B = "class App {\n    // CHANGED\n    // "
        + "x".repeat(3 * 1024 * 1024) + "\n}\n";

    private JdtlsProvider provider(String... flags) {
        return new JdtlsProvider(temp, Fixtures.fixtureArgv(flags), null, Duration.ofSeconds(8),
            new JdtlsProvider.CloseBudget(Duration.ofSeconds(2), Duration.ofMillis(100),
                Duration.ofMillis(100), Duration.ofSeconds(1)));
    }

    private static LspQuery query(Path document, long timeoutMillis, long windowMillis) {
        return new LspQuery(document.toUri(), Duration.ofMillis(timeoutMillis), Duration.ofMillis(windowMillis));
    }
}
