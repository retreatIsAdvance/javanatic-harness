package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 测试通用件：java 可执行/夹具源码定位、McpServerConfig 构造、轮询等待与可取消信号。 */
final class Fixtures {

    /** 等待上界（轮询条件类断言与 pid 出现的统一口径）。 */
    static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private Fixtures() {
    }

    static String javaBin() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /** 夹具源码：走 test-classes 资源副本定位（不依赖测试 cwd）。 */
    static String fixtureSource() {
        try {
            return Path.of(Fixtures.class.getResource("/mcp-fixture/FixtureServer.java").toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("mcp fixture resource missing", e);
        }
    }

    static McpServerConfig server(String id, Duration timeout, String... fixtureArgs) {
        return server(id, Map.of(), timeout, fixtureArgs);
    }

    static McpServerConfig server(String id, Map<String, String> env, Duration timeout, String... fixtureArgs) {
        List<String> args = new ArrayList<>(fixtureArgs.length + 1);
        args.add(fixtureSource());
        args.addAll(List.of(fixtureArgs));
        return new McpServerConfig(id, javaBin(), args, env, timeout);
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

    static long readPid(Path pidFile) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String text = readPidOrNull(pidFile);
            if (text != null) {
                return Long.parseLong(text);
            }
            sleep(10);
        }
        throw new AssertionError("pid file never written: " + pidFile);
    }

    static String readPidOrNull(Path pidFile) {
        try {
            if (!Files.exists(pidFile)) {
                return null;
            }
            String text = Files.readString(pidFile).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null; // 重启竞态:文件正被覆写
        }
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
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

    /** 仅轮询型信号（不实现 onCancel——接口默认空操作）：peer 对取消的收口只能
     * 落在 checkAbort 路径。评审 F1 钉死：该路径同样须撤销 pending + 通知远端
     * + 记撤销记忆（不清理则槽位泄漏、远端悬挂、晚到响应误报 unmatched）。 */
    static final class PollOnlySignal implements AbortSignal {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        @Override
        public void checkAbort() {
            if (cancelled.get()) {
                throw new AbortedException("poll-only cancel");
            }
        }
    }
}
