package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Content-Length 字节帧写出：header 按 UTF-8 字节数计长，绝不按字符。 */
final class LspFrames {

    private LspFrames() {
    }

    /** 编码一帧（header + 体字节）并写入、flush。 */
    static void write(OutputStream out, String body) throws IOException {
        out.write(encode(body));
        out.flush();
    }

    /** 编码一帧为完整字节序列。 */
    static byte[] encode(String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[header.length + payload.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(payload, 0, frame, header.length, payload.length);
        return frame;
    }
}
