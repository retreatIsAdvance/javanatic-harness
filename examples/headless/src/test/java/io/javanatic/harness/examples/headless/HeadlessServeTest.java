package io.javanatic.harness.examples.headless;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * it35 W3 --serve 消费面:停机裁决(同步驱动,无真信号)+ 真 SIGINT 端到端(ephemeral
 * 端口回环往返 → 首按关闭 exit 0 → close 后端口拒绝)+ 端口占用 exit 3 词面。
 * 真信号用例仅 POSIX(CI ubuntu+macos);halt(130) 会杀 JVM,只在裁决层断言。
 */
class HeadlessServeTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    // ────────── 停机裁决层(无真信号:halt 会杀 JVM) ──────────

    @Test
    void firstServeSigintClosesAndReleasesSecondHalts() throws InterruptedException {
        Recorder rec = new Recorder();
        HeadlessMain.ServeShutdown shutdown = new HeadlessMain.ServeShutdown(
            () -> rec.closes.incrementAndGet(), code -> rec.haltCodes.add(code));
        shutdown.onSignal();
        shutdown.await(); // 首按已解除:立即返回(不阻塞)
        assertThat(rec.closes).hasValue(1);
        assertThat(rec.haltCodes).isEmpty();
        shutdown.onSignal(); // 再按:逃生门
        assertThat(rec.haltCodes).containsExactly(130);
        assertThat(rec.closes).hasValue(1);
    }

    // ────────── 真 SIGINT 端到端 + 端口占用 ──────────

    /** 停机面全链:runServe 真 jsonl 后端 + ephemeral 端口 → 地址行解析 →
     *  sessionsList 往返 → SIGINT 首按 → exit 0 → close 后端口拒绝连接。 */
    @Test
    void serveEphemeralRoundTripThenSigintStops() throws Exception {
        assumeSigint();
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new JsonlPersistencePlugin(sessions)));
            rt.root().require(SessionStore.KEY)
                .create(rt.root(), Session.newId("s1"), CreateOptions.empty())
                .append(new TurnStart(1, 1));

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
            AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
            Thread serving = Thread.ofVirtual().start(() -> {
                try {
                    exit.set(HeadlessMain.runServe(rt.root(), 0, "dump-snapshot-line", out, err));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            int port = awaitServePort(outBuf);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                rpc("workbench/sessionsList", "{}", port), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"result\"").contains("\"s1\"");
            // it36 停 1:compositionDump 返回 runServe 注入的启动期快照
            HttpResponse<String> dumpResponse = client.send(
                rpc("workbench/compositionDump", "{}", port), HttpResponse.BodyHandlers.ofString());
            assertThat(dumpResponse.statusCode()).isEqualTo(200);
            assertThat(dumpResponse.body()).contains("dump-snapshot-line");

            Signal.raise(new Signal("INT")); // 首按 Ctrl-C:server close + 解除等待
            serving.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(serving.isAlive()).isFalse();
            assertThat(exit).hasValue(0);
            assertThat(outBuf.toString(StandardCharsets.UTF_8))
                .contains("JSON-RPC server 127.0.0.1:" + port);
            // close 收口:端口已释放,再连被拒(connect refused)
            assertThatThrownBy(() -> client.send(
                rpc("workbench/sessionsList", "{}", port), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);
        }
    }

    /** 端口占用失败面(停 1 设计点 2):AppBoot 真 boot 路径 → exit 3 + stderr 换端口指引。 */
    @Test
    void servePortOccupiedExitsThreeWithGuidance() throws Exception {
        // 夹具:显式关 SO_REUSEADDR 并绑 127.0.0.1。macOS(BSD)语义下默认
        // ServerSocket 双栈通配监听与回环绑定可并存,端口占用不会被检出,
        // serve 成功启动后 await 永等(SIGINT 不来 → 测试挂起)。
        try (ServerSocketChannel occupied = ServerSocketChannel.open()) {
            occupied.setOption(StandardSocketOptions.SO_REUSEADDR, false);
            occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int port = ((InetSocketAddress) occupied.getLocalAddress()).getPort();
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            HeadlessMain.RunnerOptions options =
                HeadlessMain.parse(new String[] {"--serve", "--port=" + port});
            int exit = HeadlessMain.run(options, workspace, sessions,
                new BufferedReader(new StringReader("")),
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            assertThat(exit).isEqualTo(3);
            assertThat(errBuf.toString(StandardCharsets.UTF_8))
                .contains("端口被占用").contains("--port=");
            assertThat(outBuf.toString(StandardCharsets.UTF_8)).isEmpty(); // 输出契约:失败 stdout 为空
        }
    }

    // ────────── 脚手架 ──────────

    /** SIGINT 用例前置:POSIX 且本 JVM 可装 SIGINT handler(-Xrs 等场景跳过)。 */
    private static void assumeSigint() {
        Assumptions.assumeFalse(
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
            "SIGINT 真信号用例仅 POSIX");
        try {
            SignalHandler previous = Signal.handle(new Signal("INT"), signal -> { });
            Signal.handle(new Signal("INT"), previous);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            Assumptions.abort("SIGINT 不可用: " + e.getMessage());
        }
    }

    /** 轮询 serve 地址行(handler 绑定先于打印):解析出 ephemeral 真实端口。 */
    private static int awaitServePort(ByteArrayOutputStream outBuf) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String text = outBuf.toString(StandardCharsets.UTF_8);
            int marker = text.indexOf("JSON-RPC server 127.0.0.1:");
            if (marker >= 0) {
                int start = marker + "JSON-RPC server 127.0.0.1:".length();
                int end = text.indexOf('（', start);
                if (end > start) {
                    return Integer.parseInt(text.substring(start, end));
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("20s 内未打印 serve 地址行:\n"
            + outBuf.toString(StandardCharsets.UTF_8));
    }

    private static HttpRequest rpc(String method, String params, int port) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"))
            .build();
    }

    /** 停机裁决的记录面(halt 只记码:真 halt 会杀测试 JVM)。 */
    private static final class Recorder {
        private final AtomicInteger closes = new AtomicInteger();
        private final List<Integer> haltCodes = new CopyOnWriteArrayList<>();
    }
}
