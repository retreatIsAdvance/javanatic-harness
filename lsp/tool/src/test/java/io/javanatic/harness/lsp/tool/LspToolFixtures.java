package io.javanatic.harness.lsp.tool;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import io.javanatic.harness.lsp.LspAnchor;
import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspDocument;
import io.javanatic.harness.lsp.LspEvidence;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspObservation;
import io.javanatic.harness.lsp.LspRange;
import io.javanatic.harness.lsp.LspResult;
import io.javanatic.harness.lsp.LspPosition;

/** 纯值夹具：项目根 / 文档 / 锚 / 四态结果构造（无进程、无文件访问）。 */
final class LspToolFixtures {

    static final URI ROOT = URI.create("file:///proj");
    static final URI DOC = URI.create("file:///proj/src/main/App.java");
    static final String PROJECT_HASH = "b".repeat(64);
    static final String OTHER_HASH = "c".repeat(64);

    private LspToolFixtures() {
    }

    static LspAnchor anchor(String projectHash) {
        return new LspAnchor(ROOT, UUID.fromString("00000000-0000-0000-0000-000000000001"),
            3, projectHash, new LspDocument(DOC, Optional.of("a".repeat(64))));
    }

    static LspObservation observation(LspEvidence evidence) {
        LspAnchor anchor = anchor(PROJECT_HASH);
        return new LspObservation(anchor, anchor, Duration.ofSeconds(15),
            Duration.ofSeconds(16), Instant.parse("2026-10-31T00:00:00Z"), evidence);
    }

    static LspResult<LspDiagnostic> observedDiagnostics(List<LspDiagnostic> items, LspEvidence evidence) {
        return new LspResult.Observed<>(observation(evidence), items);
    }

    static LspResult<LspLocation> observedLocations(List<LspLocation> items) {
        return new LspResult.Observed<>(observation(
            new LspEvidence(LspEvidence.Origin.REQUEST_RESPONSE, 7, OptionalInt.empty())), items);
    }

    static LspResult<LspDiagnostic> quietDiagnostics() {
        return new LspResult.Observed<>(observation(
            new LspEvidence(LspEvidence.Origin.QUIET_WINDOW, 7, OptionalInt.empty())), List.of());
    }

    static LspResult<LspDiagnostic> publishedEmptyDiagnostics() {
        return new LspResult.Observed<>(observation(
            new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 7, OptionalInt.empty())), List.of());
    }

    static LspDiagnostic diagnostic(String message, LspDiagnostic.Severity severity) {
        return new LspDiagnostic(
            new LspRange(new LspPosition(11, 4), new LspPosition(11, 10)),
            severity, Optional.empty(), Optional.empty(), message);
    }

    static LspDiagnostic codedDiagnostic(String message, String code, String source) {
        return new LspDiagnostic(
            new LspRange(new LspPosition(0, 0), new LspPosition(0, 1)),
            LspDiagnostic.Severity.ERROR, Optional.of(code), Optional.of(source), message);
    }

    static LspLocation location(String uriText, int startLine, int startChar, int endLine, int endChar) {
        return new LspLocation(URI.create(uriText),
            new LspRange(new LspPosition(startLine, startChar), new LspPosition(endLine, endChar)));
    }
}
