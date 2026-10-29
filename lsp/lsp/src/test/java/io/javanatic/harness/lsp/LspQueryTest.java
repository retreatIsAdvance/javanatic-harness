package io.javanatic.harness.lsp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** 查询参数与 wire 中立值的拒绝面，不是权限/协议集成测试。 */
class LspQueryTest {
    private static final URI FILE = URI.create("file:///project/App.java");
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void windowMustFitInsidePositiveTotalBudget() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new LspQuery(FILE, invalid, SECOND));
            assertThatIllegalArgumentException().isThrownBy(() -> new LspQuery(FILE, SECOND, invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new LspQuery(FILE, SECOND, Duration.ofMillis(1001)));
        assertThat(new LspQuery(FILE, SECOND, SECOND).observationWindow()).isEqualTo(Duration.ofMillis(1000));
        assertThatNullPointerException().isThrownBy(() -> new LspQuery(FILE, null, SECOND));
    }

    @Test
    void relativeAndOpaqueUrisAreRejectedButNoPermissionIsInvented() {
        for (URI invalid : List.of(URI.create("App.java"), URI.create("urn:app"))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new LspQuery(invalid, SECOND, SECOND));
            assertThatIllegalArgumentException().isThrownBy(() -> new LspDocument(invalid, Optional.empty()));
            assertThatIllegalArgumentException().isThrownBy(() -> new LspLocation(invalid, range()));
        }
        assertThat(new LspQuery(URI.create("file:///absent/App.java"), SECOND, SECOND).document().getPath())
            .isEqualTo("/absent/App.java");
        assertThatNullPointerException().isThrownBy(() -> new LspQuery(null, SECOND, SECOND));
    }

    @Test
    void positionRequiresNonnegativeUtf16Coordinates() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LspPosition(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new LspPosition(0, -1));
        LspPosition position = new LspPosition(4, 23);
        LspPositionQuery query = new LspPositionQuery(new LspQuery(FILE, SECOND, SECOND), position);
        assertThat(query.position()).isEqualTo(new LspPosition(4, 23));
        assertThatNullPointerException().isThrownBy(() -> new LspPositionQuery(query.query(), null));
        assertThatNullPointerException().isThrownBy(() -> new LspPositionQuery(null, position));
    }

    @Test
    void rangesRejectReverseOrderButAllowEmptyAndMultiline() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LspRange(new LspPosition(2, 0), new LspPosition(1, 9)));
        assertThatIllegalArgumentException().isThrownBy(() -> new LspRange(new LspPosition(1, 9), new LspPosition(1, 8)));
        assertThat(new LspRange(new LspPosition(1, 9), new LspPosition(2, 0)).end()).isEqualTo(new LspPosition(2, 0));
        assertThat(new LspRange(new LspPosition(0, 0), new LspPosition(0, 0)).start()).isEqualTo(new LspPosition(0, 0));
        assertThatNullPointerException().isThrownBy(() -> new LspRange(null, new LspPosition(0, 0)));
        assertThatNullPointerException().isThrownBy(() -> new LspLocation(FILE, null));
    }

    @Test
    void diagnosticMissingFieldsAreNotInvented() {
        LspDiagnostic diagnostic = new LspDiagnostic(range(), LspDiagnostic.Severity.UNSPECIFIED,
            Optional.empty(), Optional.empty(), "message");
        assertThat(diagnostic.code()).isEmpty();
        assertThat(diagnostic.source()).isEmpty();
        assertThat(diagnostic.severity()).isEqualTo(LspDiagnostic.Severity.UNSPECIFIED);
        assertThatNullPointerException().isThrownBy(() -> new LspDiagnostic(range(), null,
            Optional.empty(), Optional.empty(), "message"));
        assertThatNullPointerException().isThrownBy(() -> new LspDiagnostic(range(), LspDiagnostic.Severity.WARNING,
            Optional.empty(), Optional.empty(), null));
    }

    @Test
    void errorCodeAndCauseArePreserved() {
        IllegalStateException cause = new IllegalStateException("failure");
        LspException failure = new LspException(LspErrorCode.PROTOCOL, "bad frame", cause);
        assertThat(failure.code()).isEqualTo(LspErrorCode.PROTOCOL);
        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(new LspException(LspErrorCode.CLOSE_FAILED, "group remains")).hasNoCause();
        assertThatNullPointerException().isThrownBy(() -> new LspException(null, "bad"));
        assertThatNullPointerException().isThrownBy(() -> new LspException(LspErrorCode.IO, "bad", null));
    }

    private static LspRange range() {
        return new LspRange(new LspPosition(4, 23), new LspPosition(4, 25));
    }
}
