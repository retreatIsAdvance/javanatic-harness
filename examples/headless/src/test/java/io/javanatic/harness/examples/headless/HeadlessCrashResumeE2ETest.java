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
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-c 崩溃恢复 e2e（it19，真进程）：① `--resume` 命中写者占用——另一进程持有
 * .writer.lock 时以「写者锁冲突」fail loud（exit 3、stdout 空、不落第二会话），
 * 首写者不受扰；② 子进程在工具执行窗口被 SIGKILL——盘上尾形可判读（tool/call
 * 已落、无配对 tool/result、无 turn/end），`--resume` 不重放工具（副作用计数
 * 不变）且以恢复事实收口，新任务在同一会话续跑；kill 由观察盘上事实触发（日志
 * 出现目标行 + 副作用文件出现），非 sleep。
 *
 * 子进程与测试 JVM 同 java、同 classpath（surefire manifest-boot jar 经 -cp
 * 生效，直起 JVM 已实证）；-Duser.home 指向隔离 home，会话根 =
 * {@code <home>/.harness/sessions}（与 main 推导同一处）。
 */
class HeadlessCrashResumeE2ETest {

    @TempDir
    Path home;

    @TempDir
    Path workspace;

    private Path sessions;
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private CountDownLatch firstRequestSeen;
    private CountDownLatch releaseFirst;

    @BeforeEach
    void setUp() throws IOException {
        sessions = home.resolve(".harness").resolve("sessions");
        Files.createDirectories(sessions);
        firstRequestSeen = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
    }

    @AfterEach
    void stop() {
        releaseFirst.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resumeOnOccupiedSessionIsRefusedFailLoudWithExitThree() throws Exception {
        // 首写者（测试 JVM 内）挂住首轮请求：写者锁保持持有、轮不收敛
        String url = startServer(request -> {
            firstRequestSeen.countDown();
            try {
                releaseFirst.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return textSse("答复");
        });
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {
            "占用基线", "--provider=vendor-x", "--base-url=" + url, "--api-key=fake"});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Integer> first = runInBackground(options, out);
        assertThat(firstRequestSeen.await(20, TimeUnit.SECONDS)).as("首写者已在飞").isTrue();
        String sessionId = onlySessionId();

        // 真第二进程 --resume 同一会话：占用即拒（不等待、不并发写）
        Child child = startChild("occupied",
            "占用检查", "--resume=" + sessionId, "--provider=vendor-x",
            "--base-url=" + url, "--api-key=fake", "--workspace=" + workspace);
        assertThat(child.process().waitFor(20, TimeUnit.SECONDS)).as("占用拒绝应立即退出").isTrue();
        assertThat(child.process().exitValue()).isEqualTo(3);
        assertThat(Files.readString(child.stdout())).isEmpty(); // stdout 契约:失败为空
        assertThat(Files.readString(child.stderr()))
            .contains("写者锁冲突")
            .contains("writer lock held")
            .doesNotContain("\tat "); // fail loud 出口,非未捕获异常栈
        assertThat(sessionDirs()).as("被拒的 resume 不落第二会话").hasSize(1);

        // 首写者不受扰：照常收敛落账
        releaseFirst.countDown();
        assertThat(first.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("答复");
        assertThat(Files.readString(sessions.resolve(sessionId).resolve("log.jsonl")))
            .contains("\"type\":\"turn/end\"").contains("\"kind\":\"completed\"");
    }

    @Test
    void sigkilledChildLeavesJudgableTailAndResumeClosesWithoutReplay() throws Exception {
        assumePosix();
        Path runs = workspace.resolve("runs.txt");
        String url = startServer(request -> request == 0
            ? toolCallSse("call-crash-1", "echo run >> " + runs + "; sleep 30")
            : textSse("恢复完成"));

        Child child = startChild("crash",
            "崩溃任务", "--provider=vendor-k", "--base-url=" + url, "--api-key=fake",
            "--workspace=" + workspace);
        Path log = awaitSessionLog();
        awaitLogContains(log, "\"type\":\"tool/call\""); // 盘上事实:工具批已落账(屏障已过)
        awaitUntil("bash 副作用未出现", () -> Files.exists(runs)); // 盘上事实:工具在飞
        // 待 bash 真正 fork 出 sleep(消竞态)再快照后代:SIGKILL 不级联子进程,
        // bash/sleep 会成机器级孤儿——测尾回收,不给同机他测留残渣
        awaitUntil("bash 未进入 sleep(工具在途)", () -> hasSleepDescendant(child.process()));
        List<ProcessHandle> spawned = child.process().descendants().toList();
        child.process().destroyForcibly(); // SIGKILL:无收敛、无落账机会
        try {
            assertThat(child.process().waitFor(20, TimeUnit.SECONDS)).isTrue();

            String tail = Files.readString(log);
            assertThat(tail)
                .contains("\"type\":\"tool/call\"").contains("\"callId\":\"call-crash-1\"")
                .doesNotContain("\"type\":\"tool/result\"")
                .doesNotContain("\"type\":\"turn/end\"");
            assertThat(Files.readAllLines(runs)).hasSize(1);

            // 真实 kill 可能停在任意写点:确定性补一截撕裂尾——字节级修复与语义级恢复
            // 在同一次真实 resume 里组合跑(验收①的"组合路径")
            long nextSeq = Files.readAllLines(log).size();
            Files.writeString(log, "{\"seq\":" + nextSeq + ",\"type\":\"tool/",
                StandardOpenOption.APPEND);

            String sessionId = log.getParent().getFileName().toString();
            Child resume = startChild("resume",
                "续跑任务", "--resume=" + sessionId, "--provider=vendor-k", "--base-url=" + url,
                "--api-key=fake", "--workspace=" + workspace);
            assertThat(resume.process().waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(resume.process().exitValue()).isZero(); // 锁随进程死亡释放,可接管
            assertThat(Files.readString(resume.stdout())).contains("恢复完成");

            String recovered = Files.readString(log);
            assertThat(recovered)
                .contains("结果未知，可自行核验")
                .contains("\"kind\":\"aborted\"").contains("\"cause\":\"interrupted\"")
                .contains("\"kind\":\"completed\"");
            assertThat(countOccurrences(recovered, "\"type\":\"tool/call\"")).isEqualTo(1);
            assertThat(Files.readAllLines(runs)).as("不悄悄重做:副作用计数不变").hasSize(1);
            // 恢复后首个真实请求合法:配对 tool 消息在 wire 上(不收口即 OpenAI 配对 400)
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(1))
                .contains("\"tool_call_id\":\"call-crash-1\"")
                .contains("结果未知，可自行核验");
        } finally {
            spawned.forEach(ProcessHandle::destroyForcibly);
        }
    }

    // ────────── 假服务端 ──────────

    private String startServer(IntFunction<String> responseForRequest) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        AtomicInteger request = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseForRequest.apply(request.getAndIncrement())
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static String textSse(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":" + jsonString(text) + "}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    }

    /** OpenAI 分离形状的流式工具调用（同 openai-compat 解析口径：id/name 与 arguments 分块）。 */
    private static String toolCallSse(String callId, String command) {
        String arguments = "{\"command\":" + jsonString(command) + "}";
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":"
            + jsonString(callId) + ",\"function\":{\"name\":\"bash\",\"arguments\":\"\"}}]}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
            + "\"function\":{\"arguments\":" + jsonString(arguments) + "}}]}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
            + "data: [DONE]\n\n";
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ────────── 子进程与脚手架 ──────────

    private Child startChild(String name, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=" + home,
            "-cp", System.getProperty("java.class.path"),
            HeadlessMain.class.getName()));
        command.addAll(List.of(args));
        Path stdout = home.resolve(name + "-stdout.log");
        Path stderr = home.resolve(name + "-stderr.log");
        Process process = new ProcessBuilder(command)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
        return new Child(process, stdout, stderr);
    }

    private CompletableFuture<Integer> runInBackground(HeadlessMain.RunnerOptions options,
                                                       ByteArrayOutputStream out) {
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                exit.complete(HeadlessMain.run(options, workspace, sessions,
                    new BufferedReader(new StringReader("")),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
            } catch (Throwable t) {
                exit.completeExceptionally(t);
            }
        });
        return exit;
    }

    private List<Path> sessionDirs() throws IOException {
        try (var dirs = Files.list(sessions)) {
            return dirs.filter(Files::isDirectory).sorted().toList();
        }
    }

    private String onlySessionId() throws IOException {
        List<Path> dirs = sessionDirs();
        assertThat(dirs).as("会话目录应恰有一个").hasSize(1);
        return dirs.getFirst().getFileName().toString();
    }

    private Path awaitSessionLog() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            for (Path dir : sessionDirs()) {
                Path log = dir.resolve("log.jsonl");
                try {
                    if (Files.isRegularFile(log) && !Files.readString(log).isEmpty()) {
                        return log;
                    }
                } catch (IOException race) {
                    break; // 目录刚落、文件未就绪:下一轮再看
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("20s 内会话日志未出现");
    }

    private void awaitLogContains(Path log, String needle) throws InterruptedException {
        awaitUntil("日志未出现: " + needle, () -> {
            try {
                return Files.readString(log).contains(needle);
            } catch (IOException e) {
                return false;
            }
        });
    }

    private static void awaitUntil(String failureMessage, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("20s 内未满足: " + failureMessage);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** 后代中含 sleep 30(工具确在途)。 */
    private static boolean hasSleepDescendant(Process process) {
        return process.descendants().anyMatch(handle -> handle.info().commandLine()
            .map(line -> line.contains("sleep 30")).orElse(false));
    }

    /** SIGKILL 语义用例仅 POSIX（CI ubuntu+macos）。 */
    private static void assumePosix() {
        Assumptions.assumeFalse(
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
            "SIGKILL 子进程用例仅 POSIX");
    }

    private record Child(Process process, Path stdout, Path stderr) {
    }
}
