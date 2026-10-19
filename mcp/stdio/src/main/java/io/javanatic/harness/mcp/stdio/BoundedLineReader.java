package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.mcp.McpProtocolException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 有界换行分帧读取（T7）：单行字节数超过上限即协议失败，不无界增长内存。
 * 超限意味着残帧之后流不可再信——调用方按会话断裂处理（重连）。
 *
 * <p>上限 {@link JsonRpcPeer#MAX_FRAME_BYTES} 是传输层安全阀，与消费面
 * 256 KiB 结果上限分层（前者防无界内存，后者定模型可见大小）。
 */
final class BoundedLineReader implements Closeable {

    private final BufferedInputStream in;
    private final int maxBytes;

    BoundedLineReader(InputStream in, int maxBytes) {
        this.in = new BufferedInputStream(in, 8192);
        this.maxBytes = maxBytes;
    }

    /**
     * @return 一行（不含换行；EOF 返回 null）
     * @throws IOException 底层读失败
     * @throws McpProtocolException 单行超过 {@code maxBytes}（帧不可信）
     */
    String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) != -1) {
            if (next == '\n') {
                return stripCr(line);
            }
            if (line.size() >= maxBytes) {
                throw new McpProtocolException("mcp frame exceeds " + maxBytes + " bytes");
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
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
