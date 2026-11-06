package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspQuery;
import io.javanatic.harness.lsp.LspResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 可控文件系统采样边界 + 真实进程对端；不通过延迟修改夹具来凑观察截点。 */
class JdtlsProviderSamplingTest {

    @TempDir
    Path temp;

    @Test
    void afterSamplingCannotOutliveTheEntryDeadline() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        SamplingGate reader = new SamplingGate(document);
        try (JdtlsProvider provider = provider(reader)) {
            // 先完成初始化，以免将初始化时限误认作 after 采样时限。
            provider.diagnostics(query(document, 6000), AbortSignal.never());
            reader.blockAt.set(reader.reads.get() + 2);
            // 入口预算须在慢 runner 上仍能活着进入采样（CI macos 实撞：350ms 输给调度
            // 前置开销）；被测不变式 = 「进入采样后不得越过入口期限」，与期限数值无关。
            CompletableFuture<LspResult<LspDiagnostic>> result = pullAsync(provider, query(document, 2000));
            try {
                assertThat(reader.entered.await(8, TimeUnit.SECONDS)).isTrue();
                assertThat(result.get(8, TimeUnit.SECONDS)).isInstanceOf(LspResult.TimedOut.class);
            } finally {
                reader.release.countDown();
                result.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 513})
    void frozenCandidateSurvivesLaterPublicationAndOverflow(int laterCount) throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        SamplingGate reader = new SamplingGate(document);
        reader.blockAt.set(2);
        Path first = publication(document, "first.json", 1, 7);
        Path next = publication(document, "next.json", laterCount, 8);
        Path gate = temp.resolve("gate");
        Path receipt = temp.resolve("parsed");
        try (JdtlsProvider provider = provider(reader, "--publish-raw-on-open", first.toString(),
                "--publication-gate", gate.toString(), next.toString(), receipt.toString())) {
            CompletableFuture<LspResult<LspDiagnostic>> result = pullAsync(provider, query(document, 6000));
            try {
                assertThat(reader.entered.await(3, TimeUnit.SECONDS)).isTrue();
                Files.writeString(gate, "go");
                Fixtures.awaitTrue("later batch parsed before after completes", () -> Files.exists(receipt));
            } finally {
                Files.writeString(gate, "go");
                reader.release.countDown();
            }
            var firstResult = (LspResult.Observed<LspDiagnostic>) result.get(3, TimeUnit.SECONDS);
            assertThat(firstResult.items()).hasSize(1);
            assertThat(firstResult.observation().evidence().serverVersion()).hasValue(7);
            assertThat(firstResult.observation().evidence().origin()).isEqualTo(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS);
            if (laterCount == 513) {
                assertThatThrownBy(() -> provider.diagnostics(query(document, 6000), AbortSignal.never()))
                    .isInstanceOf(LspException.class)
                    .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
            } else {
                var later = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document, 6000), AbortSignal.never());
                assertThat(later.items()).hasSize(laterCount);
                assertThat(later.observation().evidence().serverVersion()).hasValue(8);
                assertThat(later.observation().evidence().receiveSequence())
                    .isGreaterThan(firstResult.observation().evidence().receiveSequence());
            }
        }
    }

    /** R3-2 组合反例：同轮冻结溢出 + after 采样见输入变化——PROTOCOL 不得被 Stale 分支吞掉；
     *  溢出只毒化当轮，下轮（新代）健康。同轮组合验证，不以「溢出」「变化」分别覆盖冒充。 */
    @Test
    void overflowFrozenInSameRoundAsInputChangeReportsProtocolThenRecovers() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path gate = temp.resolve("gate");
        Path overflow = publication(document, "overflow.json", 513, 7);
        Path receipt = temp.resolve("parsed");
        RewritingReader reader = new RewritingReader(document, gate, "class App { int changed; }\n", 3);
        try (JdtlsProvider provider = provider(reader, "--publication-gate",
                gate.toString(), overflow.toString(), receipt.toString())) {
            var first = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document, 6000), AbortSignal.never());
            assertThat(first.items()).isEmpty();
            LspQuery combined = new LspQuery(document.toUri(), Duration.ofMillis(6000), Duration.ofMillis(1500));
            assertThatThrownBy(() -> provider.diagnostics(combined, AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
            Fixtures.awaitTrue("overflow frame parsed in the same round", () -> Files.exists(receipt));
            var later = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document, 6000), AbortSignal.never());
            assertThat(later.items()).isEmpty();
            assertThat(later.observation().before().document().sha256())
                .isNotEqualTo(first.observation().after().document().sha256());
        }
    }

    /** R3-3 反例：close 与采样线程登记/启动须互斥——close 被登记临界区阻塞（无互斥时立即完成）；
     *  释放后两路都有界收口，观察以 CLOSED 拒绝结束，绝不在 close 之后启动新采样线程。 */
    @Test
    void closeMutuallyExcludesSamplingRegistrationAndRejectsAfterwards() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        try (JdtlsProvider provider = provider(() -> {
            registrationEntered.countDown();
            try {
                releaseRegistration.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            CompletableFuture<LspResult<LspDiagnostic>> observation = pullAsync(provider, query(document, 6000));
            assertThat(registrationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> closeDone = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    provider.close();
                    closeDone.complete(null);
                } catch (RuntimeException e) {
                    closeDone.completeExceptionally(e);
                }
            });
            try {
                Fixtures.sleep(300);
                assertThat(closeDone).isNotDone(); // 无互斥时 close 立即完成 → 确定性红
            } finally {
                releaseRegistration.countDown();
            }
            closeDone.get(5, TimeUnit.SECONDS);
            observation.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void deletionDuringWindowIsStaleWithAnAbsentAfterHash() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        SamplingGate reader = new SamplingGate(document);
        reader.blockAt.set(2);
        try (JdtlsProvider provider = provider(reader)) {
            CompletableFuture<LspResult<LspDiagnostic>> result = pullAsync(provider, query(document, 6000));
            try {
                assertThat(reader.entered.await(3, TimeUnit.SECONDS)).isTrue();
                Files.delete(document);
            } finally {
                reader.release.countDown();
            }
            var stale = (LspResult.Stale<LspDiagnostic>) result.get(3, TimeUnit.SECONDS);
            assertThat(stale.before().document().sha256()).isPresent();
            assertThat(stale.after().document().sha256()).isEmpty();
            assertThat(stale.after().generation()).isGreaterThan(stale.before().generation());
        }
    }

    @Test
    void previouslyOpenedDocumentOutsideTheQueryStillParticipatesInSamplingAndSync() throws Exception {
        Path first = Files.writeString(temp.resolve("Other.java"), "class Other {}\n");
        Path current = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path wire = temp.resolve("wire");
        try (JdtlsProvider provider = provider(Files::readAllBytes, "--record-sync-to", wire.toString(),
                "--publish-on-open-delayed", current.toUri().toString(), "1")) {
            provider.diagnostics(query(first, 6000), AbortSignal.never());
            var original = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(current, 6000), AbortSignal.never());
            assertThat(original.items()).hasSize(1);
            Files.writeString(first, "class Other { int external; }\n");
            var changed = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(current, 6000), AbortSignal.never());
            assertThat(changed.items()).isEmpty();
            assertThat(changed.observation().before().generation()).isGreaterThan(original.observation().after().generation());
            JsonNode change = new ObjectMapper().readTree(Files.readAllLines(wire).getLast());
            assertThat(change.path("method").asText()).isEqualTo("textDocument/didChange");
            assertThat(change.at("/params/textDocument/uri").asText()).isEqualTo(first.toUri().toString());
            assertThat(change.at("/params/textDocument/version").intValue()).isEqualTo(2);
            assertThat(change.at("/params/contentChanges/0/text").asText()).isEqualTo("class Other { int external; }\n");
            var stable = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(current, 6000), AbortSignal.never());
            assertThat(stable.observation().before().generation()).isEqualTo(changed.observation().after().generation());
        }
    }

    @Test
    void manifestAndDocumentHashAndDidOpenUseTheSameSampledBytes() throws Exception {
        Path source = Files.createDirectories(temp.resolve("src/main/java"));
        Path document = Files.writeString(source.resolve("App.java"), "class App {}\n");
        Path wire = temp.resolve("wire");
        AtomicInteger reads = new AtomicInteger();
        ProviderInputs.FileReader reader = path -> {
            byte[] bytes = Files.readAllBytes(path);
            if (path.equals(document) && reads.incrementAndGet() == 1) {
                Files.writeString(path, "class App { int changed; }\n");
            }
            return bytes;
        };
        try (JdtlsProvider provider = provider(reader, "--record-sync-to", wire.toString())) {
            var stale = (LspResult.Stale<LspDiagnostic>) provider.diagnostics(query(document, 6000), AbortSignal.never());
            assertThat(stale.before().document().sha256())
                .contains("7857929bc003002041fc53ce4fc1b50753a888a667ec0fbd797bc5af02866432");
            assertThat(stale.before().projectHash()).isNotEqualTo(stale.after().projectHash());
            JsonNode open = new ObjectMapper().readTree(Files.readAllLines(wire).getFirst());
            assertThat(open.at("/params/textDocument/text").asText()).isEqualTo("class App {}\n");
            assertThat(reads.get()).isEqualTo(2);
        }
    }

    @Test
    void pomChangesBetweenPullsInvalidateTheGeneration() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path pom = Files.writeString(temp.resolve("pom.xml"), "<project/>\n");
        try (JdtlsProvider provider = provider(Files::readAllBytes)) {
            var first = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document, 6000), AbortSignal.never());
            Files.writeString(pom, "<project><name>changed</name></project>\n");
            var second = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document, 6000), AbortSignal.never());
            assertThat(second.observation().before().generation()).isGreaterThan(first.observation().after().generation());
            assertThat(second.observation().before().projectHash()).isNotEqualTo(first.observation().after().projectHash());
        }
    }

    private Path publication(Path document, String name, int count, int version) throws IOException {
        ObjectMapper json = new ObjectMapper();
        ObjectNode frame = json.createObjectNode().put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics");
        ObjectNode params = frame.putObject("params").put("uri", document.toUri().toString()).put("version", version);
        var items = params.putArray("diagnostics");
        for (int index = 0; index < count; index++) {
            ObjectNode item = items.addObject().put("message", "warning").put("severity", 2);
            ObjectNode range = item.putObject("range");
            range.putObject("start").put("line", 0).put("character", 0);
            range.putObject("end").put("line", 0).put("character", 1);
        }
        return Files.writeString(temp.resolve(name), frame.toString());
    }

    private JdtlsProvider provider(ProviderInputs.FileReader reader, String... flags) {
        return new JdtlsProvider(temp, Fixtures.fixtureArgv(flags), null,
            Duration.ofSeconds(8), new JdtlsProvider.CloseBudget(Duration.ofSeconds(2),
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(1)), reader);
    }

    private JdtlsProvider provider(Runnable beforeRegistration, String... flags) {
        return new JdtlsProvider(temp, Fixtures.fixtureArgv(flags), null,
            Duration.ofSeconds(8), new JdtlsProvider.CloseBudget(Duration.ofSeconds(2),
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(1)),
            Files::readAllBytes, beforeRegistration);
    }

    private static LspQuery query(Path document, long timeoutMillis) {
        return new LspQuery(document.toUri(), Duration.ofMillis(timeoutMillis), Duration.ofMillis(100));
    }

    private static CompletableFuture<LspResult<LspDiagnostic>> pullAsync(JdtlsProvider provider, LspQuery query) {
        CompletableFuture<LspResult<LspDiagnostic>> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(provider.diagnostics(query, AbortSignal.never()));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static final class SamplingGate implements ProviderInputs.FileReader {
        private final Path document;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger blockAt = new AtomicInteger(-1);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private SamplingGate(Path document) {
            this.document = document;
        }

        @Override
        public byte[] read(Path path) throws IOException {
            if (path.equals(document) && reads.incrementAndGet() == blockAt.get()) {
                entered.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) {
                        throw new IOException("test sampling gate not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("sampling interrupted", e);
                }
            }
            return Files.readAllBytes(path);
        }
    }

    /** 第 rewriteAt 次读文档：先读旧字节再改写磁盘并开闸（读到的 before 与磁盘 after 分叉，同轮 Stale 向量）。 */
    private static final class RewritingReader implements ProviderInputs.FileReader {
        private final Path document;
        private final Path gate;
        private final String replacement;
        private final int rewriteAt;
        private final AtomicInteger reads = new AtomicInteger();

        private RewritingReader(Path document, Path gate, String replacement, int rewriteAt) {
            this.document = document;
            this.gate = gate;
            this.replacement = replacement;
            this.rewriteAt = rewriteAt;
        }

        @Override
        public byte[] read(Path path) throws IOException {
            byte[] bytes = Files.readAllBytes(path);
            if (path.equals(document) && reads.incrementAndGet() == rewriteAt) {
                Files.writeString(path, replacement);
                Files.writeString(gate, "go");
            }
            return bytes;
        }
    }
}
