package io.javanatic.harness.lsp.jdtls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 独立 JVM 对端：控制文件只在测试临时目录；停读真实 stdin，不替换 Process 管道。 */
public final class PeerBackpressureServer {
    private static final Pattern ID = Pattern.compile("\\\"id\\\":(\\d+)");

    private PeerBackpressureServer() {
    }

    public static void main(String[] args) throws Exception {
        Path control = Path.of(args[1]);
        send("{\"jsonrpc\":\"2.0\",\"method\":\"ready\"}");
        InputStream input = System.in;
        boolean halfClosed = false;
        if ("scripted".equals(args[0])) {
            read(input); // 测试发起一次真实调用/通知后才发送固定线协议载荷。
            for (String frame : Files.readAllLines(control.resolve("server-frames"))) {
                send(frame);
            }
            String reply;
            while ((reply = read(input)) != null) {
                Files.writeString(control.resolve("client-reply"), reply);
            }
            return;
        }
        if ("blocked".equals(args[0]) || "provider-blocked".equals(args[0])) {
            String initialize = read(input);
            Matcher initializeId = ID.matcher(initialize);
            if (!initialize.contains("\"method\":\"initialize\"") || !initializeId.find()) {
                throw new IllegalStateException("expected initialize before blocking");
            }
            send("{\"jsonrpc\":\"2.0\",\"id\":" + initializeId.group(1)
                + ",\"result\":{\"capabilities\":{\"textDocumentSync\":1}}}");
            if ("provider-blocked".equals(args[0]) && !read(input).contains("\"method\":\"initialized\"")) {
                throw new IllegalStateException("expected initialized before document sync");
            }
            int first = input.read();
            Files.writeString(control.resolve("write-started"), Integer.toString(first));
            boolean trafficSent = false;
            boolean floodSent = false;
            while (!Files.exists(control.resolve("resume"))) {
                if (!halfClosed && Files.exists(control.resolve("half-close"))) {
                    send("{\"jsonrpc\":\"2.0\",\"id\":\"server\","
                        + "\"method\":\"window/workDoneProgress/create\"}");
                    System.out.close();
                    halfClosed = true;
                }
                if (!trafficSent && Files.exists(control.resolve("traffic"))) {
                    send("{\"jsonrpc\":\"2.0\",\"id\":\"server\","
                        + "\"method\":\"window/workDoneProgress/create\"}");
                    send("{\"jsonrpc\":\"2.0\",\"method\":\"after-server-request\"}");
                    trafficSent = true;
                }
                if (!floodSent && Files.exists(control.resolve("flood"))) {
                    for (int i = 0; i < 256; i++) {
                        send("{\"jsonrpc\":\"2.0\",\"id\":\"flood-" + i
                            + "\",\"method\":\"window/workDoneProgress/create\"}");
                    }
                    floodSent = true;
                }
                Thread.sleep(5);
            }
            input = new SequenceInputStream(new ByteArrayInputStream(new byte[] {(byte) first}), input);
        }
        if ("sync-blocked".equals(args[0])) {
            String initialize = read(input);
            Matcher initializeId = ID.matcher(initialize);
            if (!initialize.contains("\"method\":\"initialize\"") || !initializeId.find()) {
                throw new IllegalStateException("expected initialize before sync-blocked");
            }
            send("{\"jsonrpc\":\"2.0\",\"id\":" + initializeId.group(1)
                + ",\"result\":{\"capabilities\":{\"textDocumentSync\":1}}}");
            if (!read(input).contains("\"method\":\"initialized\"")) {
                throw new IllegalStateException("expected initialized before first sync");
            }
            String firstSync = read(input);
            if (!firstSync.contains("\"method\":\"textDocument/didOpen\"")) {
                throw new IllegalStateException("expected didOpen as first sync frame");
            }
            Files.writeString(control.resolve("sync-log"), firstSync + "\n");
            int stalled = input.read(); // 只吃第二同步帧（didChange B）首字节后停读：客户端写满管道被背压。
            Files.writeString(control.resolve("second-started"), Integer.toString(stalled));
            while (!Files.exists(control.resolve("resume"))) {
                Thread.sleep(5);
            }
            if (stalled == -1) {
                throw new IllegalStateException("expected second sync frame before EOF");
            }
            input = new SequenceInputStream(new ByteArrayInputStream(new byte[] {(byte) stalled}), input);
            drain(input, control, halfClosed, true);
            return;
        }
        drain(input, control, halfClosed, false);
    }

    private static void drain(InputStream input, Path control, boolean halfClosed, boolean record) throws Exception {
        String body;
        while ((body = read(input)) != null) {
            if (record) {
                Files.writeString(control.resolve("sync-log"), body + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (halfClosed) {
                if (body.contains("\"id\":\"server\"") && body.contains("\"result\":null")) {
                    Files.writeString(control.resolve("reply-received"), body);
                    return;
                }
                continue; // stdout 已关；仍读取未完成大帧和随后应答。
            }
            if (body.contains("\"method\":\"malformed\"")) {
                System.out.write("Content-Length: bad\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                System.out.flush();
            } else if (body.contains("\"method\":\"partial\"")) {
                System.out.write("Content-Length: 50\r\n\r\n{".getBytes(StandardCharsets.US_ASCII));
                System.out.flush();
                return;
            } else if (body.contains("\"method\":\"exit\"")) {
                return;
            } else {
                if (body.contains("\"method\":\"publish\"")) {
                    send("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
                        + "\"params\":{\"uri\":\"file:///App.java\"}}");
                }
                Matcher id = ID.matcher(body);
                if (body.contains("\"method\"") && id.find()) {
                    send("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":null}");
                }
            }
        }
    }

    private static void send(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        System.out.write(("Content-Length: " + bytes.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        System.out.write(bytes);
        System.out.flush();
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            header.write(next);
            String text = header.toString(StandardCharsets.US_ASCII);
            if (text.endsWith("\r\n\r\n")) {
                int size = Integer.parseInt(text.substring("Content-Length: ".length()).trim());
                byte[] body = input.readNBytes(size);
                if (body.length != size) {
                    throw new IllegalStateException("truncated client frame");
                }
                return new String(body, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
