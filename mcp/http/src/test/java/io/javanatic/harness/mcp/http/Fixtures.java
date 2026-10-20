package io.javanatic.harness.mcp.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;

/** 测试通用件：java 可执行/夹具源码定位、夹具进程、配置构造、轮询等待。 */
final class Fixtures {

    /** 等待上界（轮询条件类断言与端口出现统一口径）。 */
    static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Fixtures() {
    }

    static String javaBin() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /** 夹具源码：走 test-classes 资源副本定位（不依赖测试 cwd）。 */
    static String fixtureSource() {
        return resourcePath("/mcp-http-fixture/FixtureHttpServer.java", "mcp http fixture");
    }

    /** 极小 stdio server（跨 transport 撞 id 用例用；只应答 initialize/tools/list）。 */
    static String minimalStdioSource() {
        return resourcePath("/mcp-http-fixture/MinimalStdioServer.java", "minimal stdio fixture");
    }

    private static String resourcePath(String resource, String what) {
        try {
            return Path.of(Fixtures.class.getResource(resource).toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(what + " resource missing", e);
        }
    }

    /**
     * 拉起夹具进程（端口 0 自动分配），等待端口文件出现。
     *
     * @param tmp 测试临时目录（端口/日志文件落此）
     * @param flags 夹具旗标（--port-file 由本方法接管，勿重复传）
     */
    static Fixture start(Path tmp, String... flags) {
        Path portFile = tmp.resolve("fixture-" + SEQ.incrementAndGet() + ".port");
        Path logFile = tmp.resolve("fixture-" + SEQ.get() + ".log");
        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("--add-modules");
        command.add("jdk.httpserver");
        command.add(fixtureSource());
        command.add("--port-file");
        command.add(portFile.toString());
        command.addAll(List.of(flags));
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
            long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new AssertionError("fixture exited early (code " + process.exitValue()
                        + "); log:\n" + readOrEmpty(logFile));
                }
                try {
                    if (Files.exists(portFile)) {
                        String text = Files.readString(portFile).trim();
                        if (!text.isEmpty()) {
                            return new Fixture(process, Integer.parseInt(text));
                        }
                    }
                } catch (IOException e) {
                    // 端口文件正被覆写:重试
                }
                sleep(20);
            }
            process.destroyForcibly();
            awaitExit(process);
            throw new AssertionError("fixture port file never written; log:\n" + readOrEmpty(logFile));
        } catch (IOException e) {
            throw new AssertionError("cannot start mcp http fixture", e);
        }
    }

    static McpHttpServerConfig config(String id, int port, Duration timeout) {
        return config(id, port, timeout, Map.of());
    }

    static McpHttpServerConfig config(String id, int port, Duration timeout,
                                      Map<String, String> headers) {
        return new McpHttpServerConfig(id, URI.create("http://127.0.0.1:" + port + "/mcp"),
            headers, timeout);
    }

    /** 生产同形客户端：不跟随重定向。 */
    static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("timed out awaiting: " + what);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while sleeping", e);
        }
    }

    static List<String> lines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file, e);
        }
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 等强杀真正生效（有界）。Windows 下子进程未退出前仍持有 stdout 重定向的
     * fixture-N.log 句柄，JUnit 随后清 @TempDir 会报「文件被另一进程占用」；
     * close 必须等退出再返回（测试体已过，失败只出在清理面）。
     */
    private static void awaitExit(Process process) {
        try {
            if (!process.waitFor(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("fixture process " + process.pid()
                    + " still alive after destroyForcibly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting fixture exit", e);
        }
    }

    /** 夹具进程句柄：close = 强制回收并等退出（夹具无优雅停机协议）。 */
    record Fixture(Process process, int port) implements AutoCloseable {

        @Override
        public void close() {
            process.destroyForcibly();
            awaitExit(process);
        }
    }

    /** 可取消 AbortSignal：镜像 AbortController 语义（first-cause-wins；取消同步触发已注册动作）。 */
    static final class TestSignal implements AbortSignal {

        private final AtomicReference<String> reason = new AtomicReference<>();
        private final List<Runnable> actions = new CopyOnWriteArrayList<>();

        void cancel(String why) {
            if (reason.compareAndSet(null, why)) {
                for (Runnable action : actions) {
                    action.run();
                }
            }
        }

        @Override
        public void checkAbort() {
            String why = reason.get();
            if (why != null) {
                throw new AbortedException("test cancel: " + why);
            }
        }

        @Override
        public void onCancel(Runnable action) {
            actions.add(action);
            if (reason.get() != null) {
                action.run(); // 注册窗口内已取消:立即执行(动作幂等,允许重复)
            }
        }
    }
}
