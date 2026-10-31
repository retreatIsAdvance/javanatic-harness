package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JdtlsProvider 观察算法 + 生命周期（设计 stop2-provider-design §4–§8，夹具 = lsp-fixture）：
 * 四态区分（QUIET_WINDOW / PUBLISHED_DIAGNOSTICS / REQUEST_RESPONSE / Stale / INITIALIZING /
 * CLOSED / TimedOut）、截点归属（窗口内到批归本次观察）、D3 无 version / D4 退出应答、
 * TERM→KILL 升级 + 专属进程组、排水到期未答 = CLOSE_FAILED、溢出 = PROTOCOL、
 * 窗口期进程死亡 = CONNECTION 而非 TimedOut、取消原样抛 AbortedException。
 */
class JdtlsProviderTest {

    private static final Duration WINDOW = Duration.ofMillis(200);
    private static final Duration GENEROUS = Duration.ofSeconds(8);

    @TempDir
    Path temp;

    private Path document;
    private final List<JdtlsProvider> owned = new ArrayList<>();

    @AfterEach
    void cleanupEvenWhenAnAssertionFails() {
        RuntimeException failure = null;
        for (JdtlsProvider provider : owned) {
            try {
                provider.close();
                assertThat(provider.pendingCount()).isZero();
                assertThat(provider.ioThreadsStopped()).isTrue();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Path writeDocument(String content) throws IOException {
        document = Files.writeString(temp.resolve("App.java"), content);
        return document;
    }

    private JdtlsProvider provider(String... flags) {
        JdtlsProvider provider = new JdtlsProvider(temp, Fixtures.fixtureArgv(flags), null,
            Duration.ofSeconds(10), JdtlsProvider.CloseBudget.defaults());
        owned.add(provider);
        return provider;
    }

    private LspQuery query(Duration window) {
        return new LspQuery(document.toUri(), GENEROUS, window);
    }

    private LspResult<LspDiagnostic> pull(JdtlsProvider provider, Duration window) {
        return provider.diagnostics(query(window), AbortSignal.never());
    }

    private LspResult<LspLocation> define(JdtlsProvider provider, Duration timeout) {
        LspQuery q = new LspQuery(document.toUri(), timeout, Duration.ofMillis(200));
        return provider.definition(new LspPositionQuery(q, new LspPosition(0, 0)), AbortSignal.never());
    }

    private static LspResult.Observed<LspDiagnostic> observed(LspResult<LspDiagnostic> result) {
        assertThat(result).isInstanceOf(LspResult.Observed.class);
        return (LspResult.Observed<LspDiagnostic>) result;
    }

    private static LspResult.Observed<LspLocation> observedLocations(LspResult<LspLocation> result) {
        assertThat(result).isInstanceOf(LspResult.Observed.class);
        return (LspResult.Observed<LspLocation>) result;
    }

    // ---- 四态：QUIET_WINDOW / PUBLISHED_DIAGNOSTICS（D3 形状） ----

    @Test
    void quietWindowOnCleanDocument() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider();
        LspResult.Observed<LspDiagnostic> obs = observed(pull(provider, WINDOW));
        assertThat(obs.observation().evidence().origin()).isEqualTo(LspEvidence.Origin.QUIET_WINDOW);
        assertThat(obs.items()).isEmpty();
        assertThat(obs.observation().evidence().serverVersion()).isEmpty();
        assertThat(obs.observation().evidence().receiveSequence()).isGreaterThanOrEqualTo(0);
        provider.close();
        assertThat(provider.exitCode()).hasValue(0);
    }

    @Test
    void publishedDiagnosticsWithoutVersionCarriesD3Shape() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--publish-no-version", document.toUri().toString());
        LspResult.Observed<LspDiagnostic> obs = observed(pull(provider, WINDOW));
        assertThat(obs.observation().evidence().origin())
            .isEqualTo(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS);
        assertThat(obs.observation().evidence().serverVersion()).isEmpty(); // D3：无 version 保留为空
        assertThat(obs.items()).hasSize(1);
        LspDiagnostic d = obs.items().get(0);
        assertThat(d.severity()).isEqualTo(LspDiagnostic.Severity.ERROR);
        assertThat(d.code()).contains("16777233");
        assertThat(d.message()).contains("Type mismatch");
        provider.close();
    }

    @Test
    void versionedPublicationKeepsServerVersionOnlyAsReference() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--publish-versioned", document.toUri().toString(), "7");
        LspResult.Observed<LspDiagnostic> obs = observed(pull(provider, WINDOW));
        assertThat(obs.observation().evidence().serverVersion()).isEqualTo(OptionalInt.of(7));
        provider.close();
    }

    @Test
    void emptyDiagnosticsArrayIsDistinctFromQuiet() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--publish-empty-on-init", document.toUri().toString());
        LspResult.Observed<LspDiagnostic> obs = observed(pull(provider, WINDOW));
        assertThat(obs.observation().evidence().origin())
            .isEqualTo(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS);
        assertThat(obs.items()).isEmpty(); // 空数组 ≠ 无批次
        provider.close();
    }

    @Test
    void publicationDuringWindowBelongsToThisPull() throws Exception {
        writeDocument("class App { }\n");
        try (JdtlsProvider provider = provider("--publish-on-open-delayed", document.toUri().toString(), "300")) {
            // 停 1：截点在完整窗口之后，窗口内已发布的批次必须纳入本次观察。
            LspResult.Observed<LspDiagnostic> first = observed(pull(provider, Duration.ofMillis(600)));
            assertThat(first.observation().evidence().origin())
                .isEqualTo(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS);
            assertThat(first.items()).hasSize(1);
            LspResult.Observed<LspDiagnostic> second = observed(pull(provider, Duration.ofMillis(600)));
            assertThat(second.items()).isEqualTo(first.items());
        }
    }

    @Test
    void externalEditsBetweenPullsInvalidateCandidatesAndSendVersionedFullText() throws Exception {
        writeDocument("class App { }\n");
        Path receipt = temp.resolve("sync.jsonl");
        try (JdtlsProvider provider = provider("--publish-no-version", document.toUri().toString(),
                "--record-sync-to", receipt.toString())) {
            LspResult.Observed<LspDiagnostic> first = observed(pull(provider, WINDOW));
            assertThat(first.items()).hasSize(1);
            Files.writeString(document, "class App { int second; }\n");
            LspResult.Observed<LspDiagnostic> second = observed(pull(provider, WINDOW));
            assertThat(second.observation().before().generation())
                .isGreaterThan(first.observation().after().generation());
            assertThat(second.items()).isEmpty();
            Files.writeString(document, "class App { int third; }\n");
            observed(pull(provider, WINDOW));
            var frames = Files.readAllLines(receipt).stream().map(line -> {
                try {
                    return new ObjectMapper().readTree(line);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }).toList();
            assertThat(frames).hasSize(3);
            assertThat(frames.get(1).path("method").asText()).isEqualTo("textDocument/didChange");
            assertThat(frames.get(1).at("/params/textDocument/version").intValue()).isEqualTo(2);
            assertThat(frames.get(1).at("/params/contentChanges/0/text").asText())
                .isEqualTo("class App { int second; }\n");
            assertThat(frames.get(2).at("/params/textDocument/version").intValue()).isEqualTo(3);
        }
    }

    // ---- 定位请求：REQUEST_RESPONSE 不可由通知合成 ----

    @Test
    void definitionReturnsLocation() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--definition", "location");
        LspResult.Observed<LspLocation> obs = observedLocations(define(provider, GENEROUS));
        assertThat(obs.observation().evidence().origin()).isEqualTo(LspEvidence.Origin.REQUEST_RESPONSE);
        assertThat(obs.items()).hasSize(1);
        assertThat(obs.items().get(0).uri().toString()).isEqualTo("file:///jdtls-fixture/Target.java");
        assertThat(obs.items().get(0).range().start()).isEqualTo(new LspPosition(10, 4));
        provider.close();
    }

    @Test
    void definitionLinkUsesTargetRange() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--definition", "link");
        LspResult.Observed<LspLocation> obs = observedLocations(define(provider, GENEROUS));
        assertThat(obs.items()).hasSize(1);
        assertThat(obs.items().get(0).uri().toString()).isEqualTo("file:///jdtls-fixture/Target.java");
        assertThat(obs.items().get(0).range().start()).isEqualTo(new LspPosition(10, 4));
        provider.close();
    }

    @Test
    void definitionNullResultIsEmptyButObserved() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--definition", "null");
        LspResult.Observed<LspLocation> obs = observedLocations(define(provider, GENEROUS));
        assertThat(obs.observation().evidence().origin()).isEqualTo(LspEvidence.Origin.REQUEST_RESPONSE);
        assertThat(obs.items()).isEmpty(); // null result 合法，空列表亦须成功响应
        provider.close();
    }

    @Test
    void referencesUseTheSameObservationBudget() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--references", "location");
        LspQuery q = new LspQuery(document.toUri(), GENEROUS, Duration.ofMillis(200));
        LspResult<LspLocation> result = provider.references(
            new LspPositionQuery(q, new LspPosition(0, 0)), AbortSignal.never());
        LspResult.Observed<LspLocation> obs = observedLocations(result);
        assertThat(obs.observation().evidence().origin()).isEqualTo(LspEvidence.Origin.REQUEST_RESPONSE);
        assertThat(obs.items()).hasSize(1);
        provider.close();
    }

    // ---- 四态：Stale / INITIALIZING / CLOSED / TimedOut ----

    @Test
    void staleWhenDocumentChangesDuringWindow() throws Exception {
        writeDocument("class App { }\n");
        Path openedMarker = temp.resolve("opened.txt");
        JdtlsProvider provider = provider("--write-on-open", openedMarker.toString());
        AtomicReference<LspResult<LspDiagnostic>> box = new AtomicReference<>();
        Thread puller = new Thread(() -> box.set(pull(provider, Duration.ofMillis(800))));
        puller.start();
        // didOpen 已出再改写；文件创建可先于内容可见，须等内容而不只等路径出现。
        Fixtures.awaitTrue("didOpen marker content",
            () -> "opened".equals(Fixtures.readFileEventually(openedMarker)));
        Files.writeString(document, "class App { int changed; }\n");
        puller.join();
        LspResult<LspDiagnostic> result = box.get();
        assertThat(result).isInstanceOf(LspResult.Stale.class);
        LspResult.Stale<LspDiagnostic> stale = (LspResult.Stale<LspDiagnostic>) result;
        assertThat(stale.before().incarnation()).isEqualTo(stale.after().incarnation());
        assertThat(stale.after().generation()).isGreaterThan(stale.before().generation());
        provider.close();
    }

    @Test
    void initializingBeforeInitializeCompletes() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--delay-init", "2000");
        // 短时限拉取：init 延迟 2s 未完成 → 有界等待到期 → INITIALIZING
        LspQuery shortQuery = new LspQuery(document.toUri(), Duration.ofMillis(300),
            Duration.ofMillis(200));
        LspResult<LspDiagnostic> result = provider.diagnostics(shortQuery, AbortSignal.never());
        assertThat(result).isInstanceOf(LspResult.NotReady.class);
        assertThat(((LspResult.NotReady<LspDiagnostic>) result).reason())
            .isEqualTo(LspResult.NotReadyReason.INITIALIZING);
        Fixtures.sleep(2500);
        assertThat(observed(pull(provider, WINDOW)).observation().evidence().origin())
            .isEqualTo(LspEvidence.Origin.QUIET_WINDOW);
        provider.close();
    }

    @Test
    void closedProviderYieldsNotReady() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider();
        provider.close();
        LspResult<LspDiagnostic> result = pull(provider, WINDOW);
        assertThat(result).isInstanceOf(LspResult.NotReady.class);
        assertThat(((LspResult.NotReady<LspDiagnostic>) result).reason())
            .isEqualTo(LspResult.NotReadyReason.CLOSED);
    }

    @Test
    void definitionIgnoreEndsInTimedOut() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--definition", "ignore");
        Duration timeout = Duration.ofMillis(1500);
        LspResult<LspLocation> result = define(provider, timeout);
        assertThat(result).isInstanceOf(LspResult.TimedOut.class);
        assertThat(((LspResult.TimedOut<LspLocation>) result).timeout()).isEqualTo(timeout);
        provider.close(); // 超时只失败该调用：会话仍可正常关闭
    }

    @Test
    void processDeathDuringWindowIsConnectionNotTimedOut() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--exit-after", "200");
        Fixtures.sleep(100); // initialize 完成后、进程退出倒计时中
        assertThatThrownBy(() -> pull(provider, Duration.ofMillis(800)))
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.CONNECTION); // 进程死亡 ≠ TimedOut，也不降级为空结果
        assertThatThrownBy(provider::close)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.CLOSE_FAILED); // 未答 shutdown 即死：不伪称清理成功
    }

    @Test
    void absentDocumentAtStartIsIoFailure() throws Exception {
        try (JdtlsProvider provider = provider()) {
            LspQuery q = new LspQuery(temp.resolve("missing.java").toUri(), GENEROUS, WINDOW);
            assertThatThrownBy(() -> provider.diagnostics(q, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code())
                .isEqualTo(LspErrorCode.IO);
        }
    }

    @Test
    void documentOutsideProjectRootIsFencedWithUnsupported() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider();
        observed(pull(provider, WINDOW)); // 先完成初始化：围栏断言本身不依赖会话时序
        // 兄弟目录故意共享路径前缀（temp = …/junit12345，outside = …/junit12345-outside）：
        // 越界判定必须是带尾部斜杠的目录前缀，而非裸字符串前缀。
        Path outside = temp.resolveSibling(temp.getFileName() + "-outside").resolve("Other.java");
        LspQuery outsideQuery = new LspQuery(outside.toUri(), GENEROUS, WINDOW);
        assertThatThrownBy(() -> provider.diagnostics(outsideQuery, AbortSignal.never()))
            .isInstanceOf(LspException.class)
            .satisfies(e -> assertThat(((LspException) e).code()).isEqualTo(LspErrorCode.UNSUPPORTED))
            .hasMessageContaining("outside project root");
        // 围栏先于文件存在性检查：Other.java 并不存在，仍必须是 UNSUPPORTED 而非 IO。
        LspPositionQuery position = new LspPositionQuery(outsideQuery, new LspPosition(0, 0));
        assertThatThrownBy(() -> provider.definition(position, AbortSignal.never()))
            .isInstanceOf(LspException.class)
            .satisfies(e -> assertThat(((LspException) e).code()).isEqualTo(LspErrorCode.UNSUPPORTED))
            .hasMessageContaining("outside project root");
        assertThatThrownBy(() -> provider.references(position, AbortSignal.never()))
            .isInstanceOf(LspException.class)
            .satisfies(e -> assertThat(((LspException) e).code()).isEqualTo(LspErrorCode.UNSUPPORTED))
            .hasMessageContaining("outside project root");
        provider.close();
        assertThat(provider.exitCode()).hasValue(0);
    }

    @Test
    void abortedSignalPropagatesAbortedException() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider();
        AbortSignal cancelled = new AbortSignal() {
            @Override
            public void checkAbort() {
                throw new AbortedException("cancelled by test");
            }
        };
        assertThatThrownBy(() -> provider.diagnostics(query(WINDOW), cancelled))
            .isInstanceOf(AbortedException.class);
        provider.close();
    }

    // ---- 生命周期矩阵：正常退出 / 幂等 / KILL 升级 / 退出应答 / CLOSE_FAILED / 多轮 ----

    @Test
    void closeIsIdempotent() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider();
        provider.close();
        provider.close(); // 第二次无操作
        assertThat(provider.exitCode()).hasValue(0);
    }

    @Test
    void termIgnoredEscalatesToKill() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = provider("--hang-on-exit", "--ignore-term");
        provider.close(); // TERM 被忽略 → KILL 升级收敛：无异常
        assertThat(provider.exitCode()).hasValue(137); // SIGKILL
    }

    @Test
    void exitDrainAnswersTailRequestBeforeClosing() throws Exception {
        writeDocument("class App { }\n");
        Path receipt = temp.resolve("answered.txt");
        JdtlsProvider provider = provider("--tail-request-then-silence", receipt.toString());
        provider.close(); // D4：排水窗内应答尾流 create 请求后才收束
        assertThat(Files.readString(receipt)).isEqualTo("answered");
        assertThat(provider.exitCode()).isPresent();
    }

    @Test
    void tailRequestAfterDrainGraceFailsClose() throws Exception {
        writeDocument("class App { }\n");
        JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--ignore-term", "--tail-request-delayed", "1200"),
            null, Duration.ofSeconds(10),
            new JdtlsProvider.CloseBudget(Duration.ofSeconds(2), Duration.ofMillis(500),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
        owned.add(provider);
        // 排水到期（500ms）后到达的请求未答 → 到期未答记未完成，不伪称全答
        assertThatThrownBy(provider::close)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.CLOSE_FAILED);
        assertThat(provider.exitCode()).hasValue(137); // 进程仍被收掉，不泄漏
    }

    @Test
    void threeSequentialRestartsAreIndependent() throws Exception {
        writeDocument("class App { }\n");
        for (int round = 1; round <= 3; round++) {
            JdtlsProvider provider = provider();
            assertThat(observed(pull(provider, WINDOW)).observation().evidence().origin())
                .isEqualTo(LspEvidence.Origin.QUIET_WINDOW);
            provider.close();
            assertThat(provider.exitCode()).hasValue(0);
        }
    }

    // ---- 专属进程组与溢出 ----

    @Test
    void dedicatedProcessGroupMatchesPid() throws Exception {
        writeDocument("class App { }\n");
        Path pidFile = temp.resolve("fixture.pid");
        JdtlsProvider provider = provider("--pid-file", pidFile.toString());
        String pidText = Fixtures.readFileEventually(pidFile);
        assertThat(pidText).isNotNull();
        long pid = Long.parseLong(pidText.trim());
        assertThat(Session.readPgid(pid)).hasValue(pid); // pgid == pid：自成 session
        assertThat(provider.dedicatedGroup()).isTrue();
        provider.close();
    }

    @Test
    void overflowMarksProtocolFailure() throws Exception {
        writeDocument("class App { }\n");
        try (JdtlsProvider provider = provider("--publish-many-on-open", document.toUri().toString(), "600")) {
            assertThatThrownBy(() -> pull(provider, WINDOW))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code())
                .isEqualTo(LspErrorCode.PROTOCOL); // 窗内溢出必须毒化本轮，不归下一次
        }
    }
}
