package io.javanatic.harness.web.tool;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 输出上限（M4 老坑的回归面）：UTF-8 完整字符边界、标记不计入上限、
 * 不整串编码；单行化元数据防伪造头部行。
 */
class WebToolCapsTest {

    @Test
    void capKeepsUtf8BoundaryAndAppendsMarker() {
        WebToolOutput.Bounded bounded = WebToolOutput.cap("ab\u2192cd", 4);

        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.text()).isEqualTo("ab" + " (output truncated)");
        assertThat(bounded.text()).doesNotContain("\uFFFD");
    }

    @Test
    void exactByteBoundaryIsNotTruncated() {
        WebToolOutput.Bounded bounded = WebToolOutput.cap("ab\u2192", 5);

        assertThat(bounded.truncated()).isFalse();
        assertThat(bounded.text()).isEqualTo("ab\u2192");
    }

    @Test
    void threeByteCharacterIsEitherWholeOrAbsent() {
        assertThat(WebToolOutput.cap("\u6F22", 2).text())
            .isEqualTo(" (output truncated)");
        assertThat(WebToolOutput.cap("\u6F22", 3).text()).isEqualTo("\u6F22");
        assertThat(WebToolOutput.cap("\u6F22\u6F22", 4).text())
            .isEqualTo("\u6F22 (output truncated)");
    }

    @Test
    void fourByteCharacterIsEitherWholeOrAbsent() {
        assertThat(WebToolOutput.cap("\uD83D\uDE00", 3).text())
            .isEqualTo(" (output truncated)");
        assertThat(WebToolOutput.cap("\uD83D\uDE00", 4).text())
            .isEqualTo("\uD83D\uDE00");
    }

    @Test
    void markerItselfIsNotCountedAgainstTheLimit() {
        WebToolOutput.Bounded bounded = WebToolOutput.cap("abcdef", 3);

        assertThat(bounded.text()).isEqualTo("abc" + " (output truncated)");
        assertThat(bounded.text().substring(0, 3).getBytes(StandardCharsets.UTF_8))
            .hasSize(3);
    }

    @Test
    void nonPositiveLimitIsAProgrammingError() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> WebToolOutput.cap("x", 0));
    }

    @Test
    void oneLineCollapsesWhitespaceSoExternalMetadataCannotForgeHeaderLines() {
        assertThat(WebToolOutput.oneLine("a\nb\tc\u00A0d  e")).isEqualTo("a b c d e");
        assertThat(WebToolOutput.oneLine("  lead and trail  ")).isEqualTo("lead and trail");
        assertThat(WebToolOutput.oneLine("")).isEmpty();
    }
}
