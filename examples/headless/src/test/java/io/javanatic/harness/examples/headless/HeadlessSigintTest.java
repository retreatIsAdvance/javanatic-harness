package io.javanatic.harness.examples.headless;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-c 取消入口：策略裁决（同步驱动,无真信号）+ 真 SIGINT 端到端（一次性 exit 4 /
 * REPL 取消当前轮不退出 / 静止期退出）。信号用例仅 POSIX（CI ubuntu+macos）；
 * halt(130) 逃生门会杀 JVM,只在策略层断言。
 */
class HeadlessSigintTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    private HttpServer server;
    private CountDownLatch firstRequestSeen;
    private CountDownLatch releaseFirst;

    @BeforeEach
    void start() throws IOException {
        firstRequestSeen = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        AtomicInteger request = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat/completions", exchange -> {
            if (request.incrementAndGet() == 1) {
                firstRequestSeen.countDown();
                try {
                    releaseFirst.await(30, TimeUnit.SECONDS); // 挂住首轮:测试期间 turn 保持未收敛
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"答复\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        releaseFirst.countDown();
        server.stop(0);
    }

    // ────────── 策略层(无真信号:halt 会杀 JVM) ──────────

    @Test
    void firstSigintWhileBusyCancelsSecondHalts() {
        CompletableFuture<Void> busy = new CompletableFuture<>();
        Recorder rec = new Recorder();
        HeadlessMain.SigintPolicy policy = policy(() -> busy, false, rec);
        policy.onSignal();
        assertThat(rec.cancels).hasValue(1);
        assertThat(rec.haltCodes).isEmpty();
        assertThat(rec.notices).hasSize(1);
        policy.onSignal(); // 上次取消尚未收敛:再按 = 强退,不再补取消
        assertThat(rec.haltCodes).containsExactly(130);
        assertThat(rec.cancels).hasValue(1);
    }

    @Test
    void sigintAtIdleRequestsExitOnlyInRepl() {
        CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);
        Recorder rec = new Recorder();
        HeadlessMain.SigintPolicy oneShot = policy(() -> idle, false, rec);
        oneShot.onSignal();
        assertThat(rec.cancels).hasValue(0);
        assertThat(rec.haltCodes).isEmpty();
        assertThat(oneShot.exitRequested()).isFalse(); // 一次性:轮已收盘,忽略
        HeadlessMain.SigintPolicy repl = policy(() -> idle, true, rec);
        repl.onSignal();
        assertThat(repl.exitRequested()).isTrue(); // REPL:静止期 Ctrl-C ≡ /exit
        assertThat(rec.cancels).hasValue(0);
    }

    @Test
    void sigintOnNewTurnAfterConvergedCancelCancelsAgainNotHalts() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> current = new AtomicReference<>(first);
        Recorder rec = new Recorder();
        HeadlessMain.SigintPolicy policy = policy(current::get, true, rec);
        policy.onSignal(); // 第一轮:首次取消
        current.set(second); // 第一轮收敛,新一轮 driver 换新 future
        policy.onSignal(); // 新轮首按:仍是取消,不能误触逃生门
        assertThat(rec.cancels).hasValue(2);
        assertThat(rec.haltCodes).isEmpty();
    }

    // ────────── 真 SIGINT 端到端 ──────────

    @Test
    void oneShotSigintConvergesToAbortedTurnAndExitFour() throws Exception {
        assumeSigint();
        HeadlessMain.RunnerOptions options = options("长任务");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CompletableFuture<Integer> exit = runInBackground(options, new StringReader(""), out, err);
        assertThat(firstRequestSeen.await(20, TimeUnit.SECONDS)).as("首轮请求已到假服务端").isTrue();
        Signal.raise(new Signal("INT"));
        assertThat(exit.get(20, TimeUnit.SECONDS)).isEqualTo(4);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty(); // 输出契约:失败 stdout 为空
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("已请求取消").contains("任务被取消: user");
        awaitLogContains("\"cause\":\"user\"");
        assertThat(sessionLog()).contains("turn/end").contains("\"kind\":\"aborted\"");
    }

    @Test
    void abortedTurnCanBeResumedInANewRun() throws Exception {
        assumeSigint();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Integer> exit = runInBackground(options("首轮任务"), new StringReader(""),
            out, new ByteArrayOutputStream());
        assertThat(firstRequestSeen.await(20, TimeUnit.SECONDS)).as("首轮请求已到假服务端").isTrue();
        Signal.raise(new Signal("INT"));
        assertThat(exit.get(20, TimeUnit.SECONDS)).isEqualTo(4);
        String sessionId = sessionId();

        HeadlessMain.RunnerOptions resume = HeadlessMain.parse(new String[] {
            "续跑", "--resume=" + sessionId, "--provider=vendor-s",
            "--base-url=" + serverUrl(), "--api-key=fake"});
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        CompletableFuture<Integer> exit2 = runInBackground(resume, new StringReader(""), out2,
            new ByteArrayOutputStream());
        assertThat(exit2.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(out2.toString(StandardCharsets.UTF_8)).contains("答复");
        assertThat(sessionLog()).contains("\"kind\":\"aborted\"").contains("答复");
    }

    @Test
    void replSigintCancelsCurrentTurnAndKeepsLoopRunning() throws Exception {
        assumeSigint();
        BlockingLineSource source = new BlockingLineSource();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Integer> exit = runInBackground(options(null), source, out,
            new ByteArrayOutputStream());
        source.pushLine("第一轮");
        assertThat(firstRequestSeen.await(20, TimeUnit.SECONDS)).as("首轮请求已到假服务端").isTrue();
        Signal.raise(new Signal("INT")); // Ctrl-C:取消当前轮
        awaitLogContains("\"cause\":\"user\""); // 收敛:aborted 落账
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("已请求取消");
        source.pushLine("第二轮"); // 未退出:下一轮照跑
        awaitLogContains("答复");
        source.eof();
        assertThat(exit.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(sessionLog()).contains("\"kind\":\"aborted\"").contains("第二轮");
    }

    @Test
    void replSigintAtIdleExitsCleanly() throws Exception {
        assumeSigint();
        BlockingLineSource source = new BlockingLineSource();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Integer> exit = runInBackground(options(null), source, out,
            new ByteArrayOutputStream());
        awaitOutContains(out, "交互模式"); // 行循环已起(handler 绑定先于此)
        Signal.raise(new Signal("INT"));
        assertThat(exit.get(20, TimeUnit.SECONDS)).isZero(); // 静止期 Ctrl-C ≡ /exit
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("已请求取消");
    }

    // ────────── 脚手架 ──────────

    private HeadlessMain.RunnerOptions options(String task) {
        String[] args = task == null
            ? new String[] {"--provider=vendor-s", "--base-url=" + serverUrl(), "--api-key=fake"}
            : new String[] {task, "--provider=vendor-s", "--base-url=" + serverUrl(), "--api-key=fake"};
        return HeadlessMain.parse(args);
    }

    private String serverUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private String sessionId() throws IOException {
        try (var dirs = Files.list(sessions)) {
            return dirs.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
        }
    }

    private CompletableFuture<Integer> runInBackground(HeadlessMain.RunnerOptions options, Reader in,
                                                       ByteArrayOutputStream out, ByteArrayOutputStream err) {
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                exit.complete(HeadlessMain.run(options, workspace, sessions, new BufferedReader(in),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8)));
            } catch (Throwable t) {
                exit.completeExceptionally(t);
            }
        });
        return exit;
    }

    private static HeadlessMain.SigintPolicy policy(Supplier<CompletableFuture<Void>> whenIdle,
                                                    boolean repl, Recorder rec) {
        return new HeadlessMain.SigintPolicy(whenIdle, () -> rec.cancels.incrementAndGet(), repl,
            code -> rec.haltCodes.add(code), rec.notices::add);
    }

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

    private void awaitOutContains(ByteArrayOutputStream bytes, String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (bytes.toString(StandardCharsets.UTF_8).contains(needle)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("20s 内屏幕输出未出现: " + needle
            + "\n实际:\n" + bytes.toString(StandardCharsets.UTF_8));
    }

    private void awaitLogContains(String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (sessionLogIfPresent().contains(needle)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("20s 内会话日志未出现: " + needle + "\n实际:\n" + sessionLogIfPresent());
    }

    private String sessionLog() {
        String log = sessionLogIfPresent();
        assertThat(log).as("会话日志应已落盘").isNotEmpty();
        return log;
    }

    private String sessionLogIfPresent() {
        try (var dirs = Files.list(sessions)) {
            Path dir = dirs.filter(Files::isDirectory).findFirst().orElse(null);
            if (dir == null) {
                return "";
            }
            Path log = dir.resolve("log.jsonl");
            return Files.isRegularFile(log) ? Files.readString(log) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 策略裁决的记录面（halt 只记码:真 halt 会杀测试 JVM,策略层之外由真信号用例覆盖）。 */
    private static final class Recorder {
        private final AtomicInteger cancels = new AtomicInteger();
        private final List<Integer> haltCodes = new CopyOnWriteArrayList<>();
        private final List<String> notices = new CopyOnWriteArrayList<>();
    }

    /** 可控行源:pushLine 决定每行到达时机,eof 给 -1（≡ /exit；不依赖管道线程竞态）。 */
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
}
