package io.javanatic.harness.lsp.jdtls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Content-Length 字节帧边界（T5 边界 2）：长度按字节、charset 恒 UTF-8；
 * 畸形 Content-Length / 超限帧 = 流级断裂（PROTOCOL），残帧后流不可再信、不挽救单帧；
 * 帧边界处干净 EOF 与帧中间截断分开（截断 = 底层读失败，交连接层分档）。
 */
class LspFrameReaderTest {

    private static final byte[] CRLF_HEADER = "Content-Length: 6\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHINESE_BODY = "诊断".getBytes(StandardCharsets.UTF_8);

    private LspFrameReader reader(byte[] bytes) {
        return reader(bytes, LspFrameReader.MAX_FRAME_BYTES);
    }

    private LspFrameReader reader(byte[] bytes, int cap) {
        return new LspFrameReader(new ByteArrayInputStream(bytes), cap);
    }

    private static byte[] frame(String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + payload.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(payload, 0, out, header.length, payload.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    @Test
    void contentLengthCountsUtf8BytesNotCharacters() throws IOException {
        assertThat(reader(concat(CRLF_HEADER, CHINESE_BODY)).readFrame()).isEqualTo("诊断");
    }

    @Test
    void invalidUtf8BreaksTheStreamInsteadOfReplacingBytes() {
        // C3 后必须是续字节；28 是 ASCII '('，不得替换成 U+FFFD 后继续解析。
        byte[] header = "Content-Length: 2\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        LspFrameReader frames = reader(concat(header, new byte[] {(byte) 0xc3, 0x28}));
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void consecutiveFramesAreReadInOrder() throws IOException {
        LspFrameReader frames = reader(concat(frame("first"), frame("second"), frame("third")));
        assertThat(frames.readFrame()).isEqualTo("first");
        assertThat(frames.readFrame()).isEqualTo("second");
        assertThat(frames.readFrame()).isEqualTo("third");
        assertThat(frames.readFrame()).isNull();
    }

    @Test
    void nonNumericContentLengthBreaksTheStream() {
        LspFrameReader frames = reader("Content-Length: twelve\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void duplicateContentLengthCannotChooseTheLastValue() {
        LspFrameReader frames = reader("Content-Length: 99\r\ncontent-length: 2\r\n\r\nok"
            .getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void missingContentLengthBreaksTheStream() {
        LspFrameReader frames = reader("Content-Type: application/vscode-jsonrpc\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void negativeContentLengthBreaksTheStream() {
        LspFrameReader frames = reader("Content-Length: -3\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void frameExceedingByteCapBreaksTheStream() {
        LspFrameReader frames = reader(frame("long body"), 5);
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void nonAsciiHeaderCannotBeSilentlyReplaced() {
        byte[] bytes = "X-Name: 诊断\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(reader(bytes)::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void excessiveHeaderLinesBreakTheStream() {
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            header.append("X-Filler: ").append(i).append("\r\n");
        }
        LspFrameReader frames = reader(header.toString().getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(frames::readFrame)
            .isInstanceOf(LspException.class)
            .extracting(e -> ((LspException) e).code())
            .isEqualTo(LspErrorCode.PROTOCOL);
    }

    @Test
    void cleanEofAtFrameBoundaryReturnsNull() throws IOException {
        assertThat(reader(new byte[0]).readFrame()).isNull();
        LspFrameReader frames = reader(frame("ok"));
        assertThat(frames.readFrame()).isEqualTo("ok");
        assertThat(frames.readFrame()).isNull();
    }

    @Test
    void truncatedBodyIsAnIoFailureNotProtocol() {
        LspFrameReader frames = reader("Content-Length: 10\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(frames::readFrame).isInstanceOf(IOException.class);
    }

    @Test
    void bareLfHeaderTerminatorsAreAccepted() throws IOException {
        LspFrameReader frames = reader("Content-Length: 2\n\nok".getBytes(StandardCharsets.US_ASCII));
        assertThat(frames.readFrame()).isEqualTo("ok");
    }

    @Test
    void writeFrameEmitsByteCountedHeaderAndBody() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LspFrames.write(out, "诊断");
        assertThat(out.toByteArray()).isEqualTo(concat(CRLF_HEADER, CHINESE_BODY));
    }

    @Test
    void writeFrameIsReadBackSymmetrically() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LspFrames.write(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
        LspFrameReader frames = reader(out.toByteArray());
        assertThat(frames.readFrame()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
    }
}
