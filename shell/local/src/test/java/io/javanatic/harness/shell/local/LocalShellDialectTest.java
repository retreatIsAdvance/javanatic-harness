package io.javanatic.harness.shell.local;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 拒绝方言匹配的纯函数面（S-c 修正）：候选解码集由 {@code native.encoding} 导出，
 * stderr 原始字节按各候选试解后逐行匹配。 */
class LocalShellDialectTest {

    private static final List<String> SIGNATURES = List.of("Access is denied", "拒绝访问");

    @Test
    void dialectCharsetsStaysUtf8OnlyForBlankUtf8AndUnknown() {
        assertThat(LocalShellExecutor.dialectCharsets(null)).containsExactly(StandardCharsets.UTF_8);
        assertThat(LocalShellExecutor.dialectCharsets("  ")).containsExactly(StandardCharsets.UTF_8);
        assertThat(LocalShellExecutor.dialectCharsets("UTF-8")).containsExactly(StandardCharsets.UTF_8);
        assertThat(LocalShellExecutor.dialectCharsets("no-such-charset")).containsExactly(StandardCharsets.UTF_8);
    }

    @Test
    void dialectCharsetsAppendsResolvableNonUtf8Native() {
        assertThat(LocalShellExecutor.dialectCharsets("GBK"))
            .containsExactly(StandardCharsets.UTF_8, Charset.forName("GBK"));
    }

    /** GBK 形态（zh-CN chcp 936 的「拒绝访问」字节）只在候选集含 native 时成串；
     * 仅 UTF-8 解码是修前形态——GBK 字节解不成串，标记漏报。 */
    @Test
    void gbkBytesMatchOnlyWithNativeCharsetInSet() {
        byte[] stderr = "拒绝访问。\r\n".getBytes(Charset.forName("GBK"));
        assertThat(LocalShellExecutor.matchesDialect(stderr, SIGNATURES,
            LocalShellExecutor.dialectCharsets("GBK"))).isTrue();
        assertThat(LocalShellExecutor.matchesDialect(stderr, SIGNATURES,
            LocalShellExecutor.dialectCharsets(null))).isFalse();
    }

    @Test
    void utf8BytesMatchWithUtf8OnlySet() {
        assertThat(LocalShellExecutor.matchesDialect(
            "Access is denied.\r\n".getBytes(StandardCharsets.UTF_8), SIGNATURES,
            LocalShellExecutor.dialectCharsets(null))).isTrue();
        assertThat(LocalShellExecutor.matchesDialect(
            "ACCESS IS DENIED".getBytes(StandardCharsets.UTF_8), SIGNATURES,
            LocalShellExecutor.dialectCharsets(null))).isTrue();
    }

    @Test
    void matchingIsLineScoped() {
        assertThat(LocalShellExecutor.matchesDialect(
            "拒绝\r\n访问".getBytes(StandardCharsets.UTF_8), SIGNATURES,
            LocalShellExecutor.dialectCharsets(null))).isFalse();
        assertThat(LocalShellExecutor.matchesDialect(
            "拒绝访问".getBytes(StandardCharsets.UTF_8), SIGNATURES,
            LocalShellExecutor.dialectCharsets(null))).isTrue();
    }
}
