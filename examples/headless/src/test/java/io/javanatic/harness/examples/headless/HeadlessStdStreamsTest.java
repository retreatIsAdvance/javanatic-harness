package io.javanatic.harness.examples.headless;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口标准流 UTF-8 化（it25 S-c locale 修复）：安装动作把 System.out/err 换成新流
 * 且字符集 = UTF-8；字节锚点走中文 + em-dash 样本（S-0 实测样本字样）。本机
 * native=UTF-8 时字符集断言无区分力（真区别在 GBK 宿主 = VM），身份断言保证
 * 安装动作本身存在、字节锚点保证编码选择本身为 UTF-8。
 */
class HeadlessStdStreamsTest {

    @Test
    void installReplacesBothStandardStreamsWithUtf8() {
        PrintStream beforeOut = System.out;
        PrintStream beforeErr = System.err;
        try {
            HeadlessMain.installUtf8StdStreams();
            assertThat(System.out).isNotSameAs(beforeOut);
            assertThat(System.err).isNotSameAs(beforeErr);
            assertThat(System.out.charset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(System.err.charset()).isEqualTo(StandardCharsets.UTF_8);
        } finally {
            System.setOut(beforeOut);
            System.setErr(beforeErr);
        }
    }

    @Test
    void utf8StreamEncodesNonAsciiAsUtf8Bytes() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (PrintStream stream = HeadlessMain.utf8PrintStream(sink)) {
            stream.println("中文—em-dash");
        }
        // println 的行尾随平台（Windows = CRLF）；本用例锚点是「中文 + em-dash 按 UTF-8 落字节」
        assertThat(sink.toByteArray())
            .isEqualTo(("中文—em-dash" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }
}
