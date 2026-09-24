package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REPL 面（S3）：裸 jh 行循环——空 stdin 干净退出、命令面分流（未知命令只提示、
 * 不进模型也不落账）、消息行流式渲染到屏并落账、--resume 无任务进 REPL。
 */
class HeadlessReplTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void emptyStdinExitsCleanly() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {"--api-key=fake"});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(options, workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(exit).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("交互模式");
    }

    @Test
    void helpAndUnknownCommandStayOffModel() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {"--api-key=fake"});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(options, workspace, sessions,
            new BufferedReader(new StringReader("/help\n/wat\n/exit\n")),
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(exit).isZero();
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("/help").contains("/exit").contains("未知命令: /wat");
        String log = onlySessionLog();
        assertThat(log).contains("command/run").contains("command/done");
        assertThat(log).doesNotContain("wat"); // 未知命令只提示,不送模型也不落账
    }

    @Test
    void helpListsCancelCommandAndIdleCancelSaysNothingInFlight() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {"--api-key=fake"});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(options, workspace, sessions,
            new BufferedReader(new StringReader("/help\n/cancel\n/exit\n")),
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(exit).isZero();
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("/cancel").contains("无进行中的轮");
        assertThat(out).doesNotContain("已请求取消"); // 静止期不假称有轮
        assertThat(onlySessionLog()).contains("command/run").contains("command/done");
    }

    @Test
    void messageLineStreamsThroughRendererAndLandsInLog() throws Exception {
        BlockingLineSource source = new BlockingLineSource();
        CountDownLatch streamed = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GatedOut out = new GatedOut(bytes, streamed, "收到");
        AtomicBoolean tripped = new AtomicBoolean();
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {
            "--provider=vendor-r", "--base-url=" + SERVER_URL, "--api-key=fake"});
        Thread.ofVirtual().start(() -> {
            source.pushLine("你好");
            try {
                tripped.set(streamed.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            source.eof(); // EOF ≡ /exit
        });
        int exit = HeadlessMain.run(options, workspace, sessions, new BufferedReader(source), out);
        assertThat(exit).isZero();
        assertThat(tripped).as("流式文本应渲染到屏幕").isTrue();
        String log = onlySessionLog();
        assertThat(log).contains("turn/start").contains("assistant/chunk");
    }

    @Test
    void resumeWithoutTaskEntersRepl() throws Exception {
        HeadlessMain.RunnerOptions first = HeadlessMain.parse(new String[] {
            "第一轮", "--provider=vendor-r", "--base-url=" + SERVER_URL, "--api-key=fake"});
        assertThat(HeadlessMain.run(first, workspace, sessions)).isZero();
        String sessionId = onlySessionId();

        HeadlessMain.RunnerOptions second = HeadlessMain.parse(new String[] {
            "--resume=" + sessionId, "--provider=vendor-r", "--base-url=" + SERVER_URL,
            "--api-key=fake"});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(second, workspace, sessions,
            new BufferedReader(new StringReader("/exit\n")),
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(exit).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("交互模式");
    }

    private String onlySessionId() throws Exception {
        try (var dirs = Files.list(sessions)) {
            return dirs.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
        }
    }

    private String onlySessionLog() throws Exception {
        return Files.readString(sessions.resolve(onlySessionId()).resolve("log.jsonl"));
    }

    /** 可控行源：pushLine 决定每行到达时机，eof 给 -1（≡ /exit；不依赖管道线程竞态）。 */
    private static final class BlockingLineSource extends Reader {

        private static final String EOF = "eof";

        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private String pending = "";
        private int offset;
        private boolean closed;

        void pushLine(String line) {
            queue.add(line + "\n");
        }

        void eof() {
            queue.add(EOF);
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (closed) {
                return -1;
            }
            if (offset >= pending.length()) {
                String next;
                try {
                    next = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                if (EOF.equals(next)) {
                    closed = true;
                    return -1;
                }
                pending = next;
                offset = 0;
            }
            int n = Math.min(len, pending.length() - offset);
            pending.getChars(offset, offset + n, cbuf, off);
            offset += n;
            return n;
        }

        @Override
        public void close() {
        }
    }

    /** 指定文本渲染到屏后放行（测试据它决定何时送 EOF，不用 sleep 猜时序）。 */
    private static final class GatedOut extends PrintStream {

        private final ByteArrayOutputStream sink;
        private final CountDownLatch reached;
        private final String needle;
        private final AtomicBoolean tripped = new AtomicBoolean();

        GatedOut(ByteArrayOutputStream sink, CountDownLatch reached, String needle) {
            super(sink, true, StandardCharsets.UTF_8);
            this.sink = sink;
            this.reached = reached;
            this.needle = needle;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            check();
        }

        @Override
        public void write(int b) {
            super.write(b);
            check();
        }

        private void check() {
            if (!tripped.get() && sink.toString(StandardCharsets.UTF_8).contains(needle)
                && tripped.compareAndSet(false, true)) {
                reached.countDown();
            }
        }
    }

    /** 本地假 OpenAI 兼容服务端：两个 delta 分块 + stop（供流式渲染与 resume 用例）。 */
    private static String startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"收\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"到\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return "http://localhost:" + server.getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SERVER_URL = startServer();
}
