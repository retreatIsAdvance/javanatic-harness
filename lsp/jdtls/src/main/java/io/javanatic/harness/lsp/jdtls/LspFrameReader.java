package io.javanatic.harness.lsp.jdtls;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;

/**
 * Content-Length 字节帧读取（T5 边界 2）：长度按字节计、charset 恒 UTF-8。
 * 畸形 Content-Length（非数字 / 缺失 / 负值 / 超头数 / 超限帧）= 流级断裂（PROTOCOL）：
 * 残帧后流不可再信，不挽救单帧。帧边界处干净 EOF 返回 null（正常结束）；
 * 帧中间截断抛 {@link IOException}（交连接层分档，不是协议畸形）。
 */
final class LspFrameReader {

    /** 单帧安全帽（传输层阀，沿 mcp/stdio 8 MiB 先例；与消费面大小上限分层）。 */
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private static final int MAX_HEADER_LINES = 32;
    private static final int MAX_HEADER_LINE_BYTES = 4096;

    private final BufferedInputStream in;
    private final int maxFrameBytes;

    LspFrameReader(InputStream in, int maxFrameBytes) {
        this.in = new BufferedInputStream(in, 8192);
        this.maxFrameBytes = maxFrameBytes;
    }

    /** 读下一帧体（不含 header）；帧边界处干净 EOF 返回 null。 */
    String readFrame() throws IOException {
        long contentLength = readHeader();
        if (contentLength < 0) {
            return null;
        }
        if (contentLength > maxFrameBytes) {
            throw protocol("frame of " + contentLength + " bytes exceeds cap " + maxFrameBytes);
        }
        byte[] body = new byte[(int) contentLength];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n == -1) {
                throw new EOFException("stream ended inside a " + contentLength + "-byte frame body");
            }
            off += n;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw protocol("frame body is not valid UTF-8");
        }
    }

    /** 读 header 块，返回 Content-Length（无帧且干净 EOF 返回 -1）；畸形即 PROTOCOL。 */
    private long readHeader() throws IOException {
        Long contentLength = null;
        for (int line = 0; line < MAX_HEADER_LINES; line++) {
            String header = readHeaderLine();
            if (header == null) {
                if (line == 0) {
                    return -1;
                }
                throw protocol("stream ended inside header block");
            }
            if (header.isEmpty()) {
                if (contentLength == null) {
                    throw protocol("header block lacks Content-Length");
                }
                return contentLength;
            }
            int colon = header.indexOf(':');
            if (colon <= 0) {
                throw protocol("malformed header line");
            }
            String name = header.substring(0, colon).trim();
            if ("Content-Length".equalsIgnoreCase(name)) {
                if (contentLength != null) {
                    throw protocol("duplicate Content-Length");
                }
                contentLength = parseLength(header.substring(colon + 1).trim());
            }
            // 其余 header（如 Content-Type）忽略
        }
        throw protocol("header block exceeds " + MAX_HEADER_LINES + " lines");
    }

    /** 纯数字且非负才合法；空、非数字、超范围都按流级断裂。 */
    private long parseLength(String value) {
        if (value.isEmpty()) {
            throw protocol("empty Content-Length");
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                throw protocol("non-numeric Content-Length");
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw protocol("Content-Length out of range");
        }
    }

    /** 读一行 header（不含行终止符，CRLF / LF 均容忍）；干净 EOF 返回 null。 */
    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) != -1) {
            if (next > 0x7f) {
                throw protocol("header is not ASCII");
            }
            if (next == '\n') {
                return stripCr(line);
            }
            if (line.size() >= MAX_HEADER_LINE_BYTES) {
                throw protocol("header line exceeds " + MAX_HEADER_LINE_BYTES + " bytes");
            }
            line.write(next);
        }
        return line.size() == 0 ? null : stripCr(line);
    }

    private static String stripCr(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    private static LspException protocol(String detail) {
        return new LspException(LspErrorCode.PROTOCOL, "lsp frame stream broken: " + detail);
    }
}
