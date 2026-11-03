package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** 测试通用件：java 可执行 / 夹具源码定位、夹具进程启动、轮询等待与 pid 读取。 */
final class Fixtures {

    /** 等待上界（轮询条件类断言与 pid 出现的统一口径）。 */
    static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private Fixtures() {
    }

    /** 平台判定（测试面唯一入口，生产 JdtlsLaunch 有等价私有判定）。 */
    static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    static String javaBin() {
        String executable = windows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /** 信号退出码的平台映射：POSIX = 128+信号（SIGTERM 143 / SIGKILL 137），Windows TerminateProcess 恒 1。 */
    static int signalExit(int signal) {
        return windows() ? 1 : 128 + signal;
    }

    /** 夹具源码：走 test-classes 资源副本定位（不依赖测试 cwd）。 */
    static String fixtureSource() {
        try {
            return Path.of(Fixtures.class.getResource("/lsp-fixture/LspFixtureServer.java").toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("lsp fixture resource missing", e);
        }
    }

    /** 夹具完整 argv（含 --ignore-term 时的 jdk.unsupported 模块）；Provider 测试复用同一形状。 */
    static List<String> fixtureArgv(String... fixtureArgs) {
        List<String> argv = new ArrayList<>();
        argv.add(javaBin());
        for (String arg : fixtureArgs) {
            if ("--ignore-term".equals(arg)) {
                argv.add("--add-modules");
                argv.add("jdk.unsupported");
                break;
            }
        }
        argv.add(fixtureSource());
        argv.addAll(List.of(fixtureArgs));
        return argv;
    }

    /** 启动 LSP 夹具进程（Content-Length 帧）；TERM 忽略档需 jdk.unsupported。 */
    static Process launchFixture(String... fixtureArgs) throws IOException {
        return new ProcessBuilder(fixtureArgv(fixtureArgs))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
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

    static void awaitProcessExit(Process process) {
        awaitTrue("process exit", () -> !process.isAlive());
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while sleeping", e);
        }
    }

    /** 轮询读取文件内容；超时返回 null（由调用方条件判断收口）。 */
    static String readFileEventually(Path file) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String text = readFileOrNull(file);
            if (text != null) {
                return text;
            }
            sleep(25);
        }
        return null;
    }

    private static String readFileOrNull(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String text = Files.readString(file);
            return text.isEmpty() ? null : text; // 空文件 = 对端尚未写完（writeString 非原子），继续轮询
        } catch (IOException e) {
            return null;
        }
    }
}
