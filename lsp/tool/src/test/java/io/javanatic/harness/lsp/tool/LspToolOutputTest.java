package io.javanatic.harness.lsp.tool;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果文本四态逐字措辞（停 2 §8 纪律，用户点名评审重点）：
 * 空结果只称「拉取时点无诊断 / 本次请求返回 0 个位置」，禁令面断言把
 * 禁词类措辞钉死为必红突变。
 */
class LspToolOutputTest {

    private static final LspToolConfig CONFIG = LspToolConfig.parse(
        Map.of(), "lsp-tool");

    @Test
    void quietWindowTextIsVerbatim() {
        String text = LspToolOutput.diagnostics(LspToolFixtures.quietDiagnostics(), CONFIG);
        assertThat(text).isEqualTo("拉取成功（hash bbbbbbbb）、项目代稳定（前后锚相同）、"
            + "完整等待窗（elapsed 16.0s ≥ required 15.0s），拉取时点无诊断。");
    }

    @Test
    void quietWindowNeverClaimsProjectIsErrorFree() {
        String text = LspToolOutput.diagnostics(LspToolFixtures.quietDiagnostics(), CONFIG);
        assertThat(text).contains("拉取时点无诊断");
        assertThat(text).doesNotContain("项目无错误", "分析完成", "诊断已同步", "真零");
    }

    @Test
    void publishedEmptyBatchNamesZeroAndNeverClaimsErrorFree() {
        String text = LspToolOutput.diagnostics(LspToolFixtures.publishedEmptyDiagnostics(), CONFIG);
        assertThat(text).isEqualTo("拉取成功（hash bbbbbbbb）；LS 发布空诊断批次（0 条）。");
        assertThat(text).doesNotContain("项目无错误", "无诊断完成");
    }

    @Test
    void observedDiagnosticsRenderAnchorSeverityAndPosition() {
        LspResult<LspDiagnostic> result = LspToolFixtures.observedDiagnostics(List.of(
            LspToolFixtures.diagnostic("missing semicolon", LspDiagnostic.Severity.ERROR),
            LspToolFixtures.diagnostic("unused import", LspDiagnostic.Severity.WARNING)),
            new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 7, OptionalInt.empty()));
        String text = LspToolOutput.diagnostics(result, CONFIG);
        assertThat(text).isEqualTo("拉取成功（hash bbbbbbbb，项目代 3，等待窗 16.0s）；诊断 2 条：\n"
            + "  src/main/App.java:11:4 [ERROR] missing semicolon\n"
            + "  src/main/App.java:11:4 [WARNING] unused import");
    }

    @Test
    void codeAndSourceAreAttachedWhenPresent() {
        LspResult<LspDiagnostic> result = LspToolFixtures.observedDiagnostics(List.of(
            LspToolFixtures.codedDiagnostic("cannot resolve", "16777233", "Java")),
            new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 7, OptionalInt.empty()));
        String text = LspToolOutput.diagnostics(result, CONFIG);
        assertThat(text).contains("src/main/App.java:0:0 [ERROR] cannot resolve (16777233)");
    }

    @Test
    void notReadyNamesTheClientStateNotAResult() {
        String text = LspToolOutput.diagnostics(
            new LspResult.NotReady<>(LspResult.NotReadyReason.SYNCHRONIZING), CONFIG);
        assertThat(text).isEqualTo("未就绪（SYNCHRONIZING）——不是诊断结果。");
    }

    @Test
    void staleNamesTheAnchorChange() {
        String text = LspToolOutput.diagnostics(
            new LspResult.Stale<>(LspToolFixtures.anchor(LspToolFixtures.PROJECT_HASH),
                LspToolFixtures.anchor(LspToolFixtures.OTHER_HASH)), CONFIG);
        assertThat(text).isEqualTo("观察过期（前后锚不同：bbbbbbbb → cccccccc）——不包装为本次观察结果。");
    }

    @Test
    void timedOutIsNotNoDiagnostics() {
        String text = LspToolOutput.diagnostics(new LspResult.TimedOut<>(Duration.ofSeconds(30)), CONFIG);
        assertThat(text).isEqualTo("超时（30s）——不是无诊断。");
    }

    @Test
    void lspExceptionBecomesCodedErrorText() {
        String text = LspToolOutput.error(new LspException(LspErrorCode.IO, "cannot sample inputs"));
        assertThat(text).isEqualTo("LSP 错误（IO）：cannot sample inputs");
    }

    @Test
    void locationsRenderZeroBasedRanges() {
        LspResult<LspLocation> result =
            LspToolFixtures.observedLocations(List.of(
                LspToolFixtures.location("file:///proj/src/main/App.java", 5, 2, 5, 9),
                LspToolFixtures.location("file:///proj/src/main/Util.java", 20, 0, 20, 4)));
        String text = LspToolOutput.locations(result, CONFIG);
        assertThat(text).isEqualTo("拉取成功（hash bbbbbbbb）；位置 2 条：\n"
            + "  src/main/App.java:5:2–5:9\n"
            + "  src/main/Util.java:20:0–20:4");
    }

    @Test
    void emptyLocationsNameZeroResultsNotAbsentSymbol() {
        LspResult<LspLocation> result =
            LspToolFixtures.observedLocations(List.of());
        String text = LspToolOutput.locations(result, CONFIG);
        assertThat(text).isEqualTo("拉取成功（hash bbbbbbbb）；本次请求返回 0 个位置。");
        assertThat(text).doesNotContain("符号不存在", "无定义");
    }

    @Test
    void locationNotReadyNamesTheClientState() {
        String text = LspToolOutput.locations(
            new LspResult.NotReady<>(LspResult.NotReadyReason.INITIALIZING), CONFIG);
        assertThat(text).isEqualTo("未就绪（INITIALIZING）——不是位置结果。");
    }

    @Test
    void countCapKeepsAnchorHeaderAndAppendsMarker() {
        LspToolConfig capped = LspToolConfig.parse(
            Map.of("maxDiagnostics", 2), "lsp-tool");
        LspResult<LspDiagnostic> result = LspToolFixtures.observedDiagnostics(List.of(
            LspToolFixtures.diagnostic("first", LspDiagnostic.Severity.ERROR),
            LspToolFixtures.diagnostic("second", LspDiagnostic.Severity.ERROR),
            LspToolFixtures.diagnostic("third", LspDiagnostic.Severity.ERROR)),
            new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 7, OptionalInt.empty()));
        String text = LspToolOutput.diagnostics(result, capped);
        assertThat(text).contains("诊断 3 条：")
            .contains("first")
            .contains("second")
            .doesNotContain("third")
            .endsWith("…（截断）");
    }

    @Test
    void byteCapCutsAtUtf8BoundaryAndKeepsAnchorHeader() {
        LspToolConfig capped = LspToolConfig.parse(
            Map.of("maxOutputBytes", 120), "lsp-tool");
        LspResult<LspDiagnostic> result = LspToolFixtures.observedDiagnostics(List.of(
            LspToolFixtures.diagnostic("\u6F22".repeat(80), LspDiagnostic.Severity.ERROR)),
            new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 7, OptionalInt.empty()));
        String text = LspToolOutput.diagnostics(result, capped);
        assertThat(text.lines().findFirst()).hasValueSatisfying(
            first -> assertThat(first).contains("拉取成功（hash bbbbbbbb"));
        assertThat(text).endsWith(" (output truncated)");
        String body = text.substring(text.indexOf('\n') + 1);
        String withoutMarker = body.substring(0, body.length() - " (output truncated)".length());
        assertThat(withoutMarker.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(120);
    }
}
