package io.javanatic.harness.lsp.jdtls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspPositionQuery;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspQuery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 wire 的坏数据不能被跳过并冒充空观察。 */
class JdtlsProviderProtocolTest {

    @TempDir
    Path temp;

    @Test
    void missingDiagnosticsArrayFailsProtocolRatherThanQuiet() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path frame = Files.writeString(temp.resolve("publish.json"),
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":\""
                + document.toUri() + "\"}}");
        JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--publish-raw-on-open", frame.toString()), null,
            Duration.ofSeconds(8), JdtlsProvider.CloseBudget.defaults());
        try {
            assertThatThrownBy(() -> provider.diagnostics(new LspQuery(document.toUri(),
                Duration.ofSeconds(8), Duration.ofMillis(200)), AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
        } finally {
            closeBroken(provider);
        }
    }

    private static final String RANGE = "{\"start\":{\"line\":0,\"character\":1},"
        + "\"end\":{\"line\":0,\"character\":2}}";
    private static final String DIAGNOSTIC = "{\"range\":" + RANGE + ",\"message\":\"warning\",\"severity\":2}";

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"diagnostics\":[]}",
        "{\"uri\":\"relative\",\"diagnostics\":[]}",
        "{\"uri\":\"file:///App.java\",\"version\":4294967296,\"diagnostics\":[]}",
        "{\"uri\":\"file:///App.java\",\"version\":\"1\",\"diagnostics\":[]}"})
    void malformedPublicationEnvelopeFailsProtocol(String params) throws Exception {
        assertBadPublication(params);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"line\":4294967296,\"character\":0}",
        "{\"line\":0,\"character\":4294967296}", "{\"line\":-1,\"character\":0}",
        "{\"line\":0.0,\"character\":0}", "{\"line\":1,\"character\":0}"})
    void malformedDiagnosticRangeFailsWholeBatch(String start) throws Exception {
        String item = "{\"range\":{\"start\":" + start
            + ",\"end\":{\"line\":0,\"character\":2}},\"message\":\"bad\"}";
        assertBadPublication("{\"uri\":\"file:///App.java\",\"diagnostics\":[" + DIAGNOSTIC + "," + item + "]}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"message\":\"bad\"}",
        "{\"range\":RANGE,\"message\":3}", "{\"range\":RANGE}",
        "{\"range\":RANGE,\"message\":\"bad\",\"severity\":4294967298}",
        "{\"range\":RANGE,\"message\":\"bad\",\"source\":{}}",
        "{\"range\":RANGE,\"message\":\"bad\",\"code\":4294967296}"})
    void malformedDiagnosticIsNotFiltered(String item) throws Exception {
        assertBadPublication("{\"uri\":\"file:///App.java\",\"diagnostics\":["
            + item.replace("RANGE", RANGE) + "]}");
    }

    private void assertBadPublication(String params) throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path frame = Files.writeString(temp.resolve("publish.json"),
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":" + params + "}");
        JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--publish-raw-on-open", frame.toString()), null,
            Duration.ofSeconds(8), shortClose());
        try {
            assertThatThrownBy(() -> provider.diagnostics(query(document), AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
            assertThatThrownBy(() -> provider.diagnostics(query(document), AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
        } finally {
            closeBroken(provider);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "[null]", "[{}]", "{\"uri\":\"relative\",\"range\":RANGE}",
        "{\"uri\":\"file:///Target.java\",\"range\":{\"start\":{\"line\":4294967296,\"character\":0},"
            + "\"end\":{\"line\":0,\"character\":1}}}"})
    void malformedLocationCannotBecomeEmptySuccess(String result) throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path response = Files.writeString(temp.resolve("result.json"), result.replace("RANGE", RANGE));
        try (JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--location-result-from", response.toString()), null,
            Duration.ofSeconds(8), shortClose())) {
            assertThatThrownBy(() -> provider.definition(new LspPositionQuery(query(document),
                new LspPosition(0, 0)), AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
        }
    }

    @Test
    void referencesIncludeDeclarationAndCapabilitiesDoNotPromiseConfiguration() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path wire = temp.resolve("wire.jsonl");
        try (JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--record-wire-to", wire.toString()), null,
            Duration.ofSeconds(8), shortClose())) {
            provider.references(new LspPositionQuery(query(document), new LspPosition(0, 0)), AbortSignal.never());
            for (String line : Files.readAllLines(wire)) {
                JsonNode frame = new ObjectMapper().readTree(line);
                switch (frame.path("method").asText()) {
                    case "initialize" -> assertThat(frame.at("/params/capabilities/workspace/configuration")
                        .asBoolean()).isFalse();
                    case "textDocument/references" -> assertThat(frame.at("/params/context/includeDeclaration")
                        .asBoolean()).isTrue();
                    default -> { }
                }
            }
        }
    }

    @Test
    void warningOnlyPublicationRemainsNonEmpty() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path frame = Files.writeString(temp.resolve("publish.json"),
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":\""
                + document.toUri() + "\",\"version\":-7,\"diagnostics\":[" + DIAGNOSTIC + "]}}");
        try (JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--publish-raw-on-open", frame.toString()), null,
            Duration.ofSeconds(8), shortClose())) {
            var result = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document), AbortSignal.never());
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().getFirst().severity()).isEqualTo(LspDiagnostic.Severity.WARNING);
            assertThat(result.observation().evidence().origin()).isEqualTo(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS);
            assertThat(result.observation().evidence().serverVersion()).hasValue(-7);
        }
    }

    /** R3-4：合法 JSON-RPC 包络中 initialize 业务结果不合法（result:null/42、无 capabilities、
     *  无可用 textDocumentSync）不得冒充初始化成功——结构与非零同步能力独立验证。
     *  等 handshake 失败落定（rollback 已关闭 peer）后再拉取：钉死「已存储失败优先于健康检查浮出」，
     *  健康检查抢先会把校验失败永久掩盖为 CONNECTION。
     *  落定以夹具进程死亡为准（rollback 的 stop 先于 KILL：死亡 ⇒ peer 已标 broken）；
     *  定长 sleep 不可靠——夹具源码模式启动约 400ms，失败可能尚未落定，
     *  等待循环经 join 浮出 PROTOCOL，掩码恢复突变会被漏检。 */
    @ParameterizedTest
    @ValueSource(strings = {"null", "scalar", "no-caps", "sync-none", "sync-zero",
        "sync-openclose-only", "sync-change-only"})
    void initializeResultWithoutUsableSyncFailsProtocol(String mode) throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        Path pidFile = temp.resolve("fixture.pid");
        JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--init-result", mode, "--pid-file", pidFile.toString()), null,
            Duration.ofSeconds(8), shortClose());
        try {
            Fixtures.awaitTrue("fixture pid file", () -> Fixtures.readFileEventually(pidFile) != null);
            long pid = Long.parseLong(Fixtures.readFileEventually(pidFile));
            Fixtures.awaitTrue("fixture process exit after failed initialize",
                () -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertThatThrownBy(() -> provider.diagnostics(query(document), AbortSignal.never()))
                .isInstanceOf(LspException.class)
                .extracting(e -> ((LspException) e).code()).isEqualTo(LspErrorCode.PROTOCOL);
        } finally {
            closeBroken(provider);
        }
    }

    /** 正例：对象形态 textDocumentSync（openClose + change:2，真实 JDT LS 形状）必须被接受。 */
    @Test
    void initializeResultWithObjectSyncAcceptsIncrementalShape() throws Exception {
        Path document = Files.writeString(temp.resolve("App.java"), "class App {}\n");
        try (JdtlsProvider provider = new JdtlsProvider(temp,
            Fixtures.fixtureArgv("--init-result", "sync-object"), null,
            Duration.ofSeconds(8), shortClose())) {
            var result = (LspResult.Observed<LspDiagnostic>) provider.diagnostics(query(document), AbortSignal.never());
            assertThat(result.items()).isEmpty();
        }
    }

    private static LspQuery query(Path document) {
        return new LspQuery(document.toUri(), Duration.ofSeconds(8), Duration.ofMillis(200));
    }

    private static JdtlsProvider.CloseBudget shortClose() {
        return new JdtlsProvider.CloseBudget(Duration.ofSeconds(2), Duration.ofMillis(100),
            Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    private static void closeBroken(JdtlsProvider provider) {
        try {
            provider.close();
        } catch (LspException e) {
            // 断裂流无法答 shutdown，但必须仍回收该测试拥有的进程。
            assertThat(e.code()).isEqualTo(LspErrorCode.CLOSE_FAILED);
        }
        assertThat(provider.exitCode()).isPresent();
        assertThat(ProcessHandle.of(provider.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
