package io.javanatic.harness.shell.bash.local;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本机 bash 执行:输出/退出码/超时击杀/取消击杀进程树/输出上限截断/环境透传。 */
class LocalBashExecutorTest {

    @TempDir
    Path cwd;

    /** 显式透传策略（本测试文件测执行语义,沙箱行为归 SandboxSeatbeltTest）。 */
    private static final SandboxPolicy UNCONFINED =
        new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, Path.of("/"));

    private final LocalBashExecutor executor = new LocalBashExecutor(new BashLocalOptions(64 * 1024), null);

    /** 测试用可取消信号:checkAbort 与 onCancel 双通道(与 AbortController 语义一致)。 */
    private static final class Cancellable implements AbortSignal {
        private volatile boolean cancelled;
        private final List<Runnable> actions = new ArrayList<>();

        void cancel() {
            cancelled = true;
            actions.forEach(Runnable::run);
        }

        @Override
        public void checkAbort() {
            if (cancelled) {
                throw new AbortedException("test-cancel");
            }
        }

        @Override
        public void onCancel(Runnable action) {
            actions.add(action);
        }
    }

    private ShellRequest request(String command) {
        return new ShellRequest(command, cwd, Duration.ofSeconds(10), null, UNCONFINED);
    }

    @Test
    void capturesStdoutStderrExitCodeAndDuration() throws Exception {
        ShellResult result = executor.execute(request("echo hello; echo oops >&2; exit 3"),
            AbortSignal.never());
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEqualTo("hello\n");
        assertThat(result.stderr()).isEqualTo("oops\n");
        assertThat(result.duration()).isNotNull();
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    void timeoutKillsProcessTreeFast() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(
                new ShellRequest("sleep 30", cwd, Duration.ofMillis(100), null, UNCONFINED), AbortSignal.never()))
            .isInstanceOf(TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void cancelKillsProcessTreeIncludingGrandchildren() throws Exception {
        Cancellable signal = new Cancellable();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                executor.execute(request("sleep 30 & echo $!; sleep 30"), signal);
            } catch (AbortedException expectedOnCancel) {
                // 取消路径以 AbortedException 收尾
            } catch (Exception other) {
                throw new IllegalStateException(other);
            }
        });
        OptionalLong grandchildPid = awaitSleepPid(5000);
        assertThat(grandchildPid.isPresent()).isTrue();

        signal.cancel();
        runner.join(5000);
        long pid = grandchildPid.orElseThrow();
        assertThat(eventually(2000, () -> ProcessHandle.of(pid).isEmpty())).isTrue();
    }

    @Test
    void oversizedOutputTruncatedToCap() throws Exception {
        LocalBashExecutor capped = new LocalBashExecutor(new BashLocalOptions(1000), null);
        ShellResult result = capped.execute(
            request("head -c 200000 /dev/zero | tr '\\0' 'a'"), AbortSignal.never());
        assertThat(result.stdout()).hasSize(1000);
        assertThat(result.outputTruncated()).isTrue();
    }

    @Test
    void envIsPassedThrough() throws Exception {
        ShellResult result = executor.execute(
            new ShellRequest("echo $JH_TEST_VAR", cwd, Duration.ofSeconds(10),
                Map.of("JH_TEST_VAR", "passed"), UNCONFINED), AbortSignal.never());
        assertThat(result.stdout()).isEqualTo("passed\n");
    }

    /** 轮询进程表找 bash 的后台 sleep 孙进程 pid。 */
    private static OptionalLong awaitSleepPid(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            OptionalLong pid = ProcessHandle.allProcesses()
                .filter(handle -> handle.info().commandLine()
                    .map(cmd -> cmd.contains("sleep 30")).orElse(false))
                .mapToLong(ProcessHandle::pid)
                .findFirst();
            if (pid.isPresent()) {
                return pid;
            }
            Thread.sleep(10);
        }
        return OptionalLong.empty();
    }

    private static boolean eventually(long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
