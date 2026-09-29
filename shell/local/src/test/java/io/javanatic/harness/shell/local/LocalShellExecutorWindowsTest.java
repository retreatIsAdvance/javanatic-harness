package io.javanatic.harness.shell.local;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

/** Windows 腿的真执行（pwsh -Command）：输出/退出码/环境透传/超时与取消的进程树击杀。
 * 只在 Windows 宿主（S-c CI job / 参照 VM）跑；argv 形状与 fail-loud 归
 * {@link LocalShellPlatformTest}，POSIX 腿归 {@link LocalShellExecutorTest}。 */
@EnabledOnOs(OS.WINDOWS)
class LocalShellExecutorWindowsTest {

    @TempDir
    Path cwd;

    /** 显式透传策略（本测试文件测执行语义,沙箱行为归 WindowsAcl 相关测试）。 */
    private static final SandboxPolicy UNCONFINED =
        new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, Path.of("C:\\"));

    private final LocalShellExecutor executor = new LocalShellExecutor(new LocalShellOptions(64 * 1024), null);

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
        ShellResult result = executor.execute(
            request("Write-Output 'hello'; [Console]::Error.WriteLine('oops'); exit 3"), AbortSignal.never());
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEqualTo("hello" + System.lineSeparator());
        assertThat(result.stderr()).isEqualTo("oops" + System.lineSeparator());
        assertThat(result.duration()).isNotNull();
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    void envIsPassedThrough() throws Exception {
        ShellResult result = executor.execute(
            new ShellRequest("Write-Output $env:JH_TEST_VAR", cwd, Duration.ofSeconds(10),
                Map.of("JH_TEST_VAR", "passed"), UNCONFINED), AbortSignal.never());
        assertThat(result.stdout()).isEqualTo("passed" + System.lineSeparator());
    }

    @Test
    void timeoutKillsProcessTreeFast() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(
                new ShellRequest("Start-Sleep -Seconds 30", cwd, Duration.ofMillis(100), null, UNCONFINED),
                AbortSignal.never()))
            .isInstanceOf(TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    /** 取消杀掉孙进程：pwsh 经 Start-Process 起真孙进程，pid 落文件供回收断言。 */
    @Test
    void cancelKillsProcessTreeIncludingGrandchildren() throws Exception {
        String script = "$p = Start-Process pwsh -ArgumentList '-NoProfile','-NonInteractive',"
            + "'-Command','Start-Sleep -Seconds 30' -PassThru -NoNewWindow; "
            + "Set-Content -Path 'grandchild.pid' -Value $p.Id; Start-Sleep -Seconds 30";
        Cancellable signal = new Cancellable();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                executor.execute(request(script), signal);
            } catch (AbortedException expectedOnCancel) {
                // 取消路径以 AbortedException 收尾
            } catch (Exception other) {
                throw new IllegalStateException(other);
            }
        });
        OptionalLong grandchildPid = awaitPidFile(cwd.resolve("grandchild.pid"), 5000);
        assertThat(grandchildPid.isPresent()).isTrue();

        signal.cancel();
        runner.join(5000);
        long pid = grandchildPid.orElseThrow();
        assertThat(eventually(2000, () -> ProcessHandle.of(pid).isEmpty())).isTrue();
    }

    /** 轮询孙进程 pid 文件（pwsh 落盘后测试才拿得到真 pid）。 */
    private static OptionalLong awaitPidFile(Path pidFile, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.isRegularFile(pidFile)) {
                    String text = Files.readString(pidFile).strip();
                    if (!text.isEmpty()) {
                        return OptionalLong.of(Long.parseLong(text));
                    }
                }
            } catch (Exception notYetComplete) {
                // 文件已现但内容未落全：继续轮询
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

    /** 运行期 fail-closed belt：组合未提供沙箱 provider 时受限请求拒绝执行。 */
    @Test
    void confiningRequestWithoutProviderFailsClosed() {
        assertThatThrownBy(() -> executor.execute(
            new ShellRequest("Write-Output hi", cwd, Duration.ofSeconds(5), null,
                new SandboxPolicy(SandboxMode.READ_ONLY, cwd)),
            AbortSignal.never()))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("refusing to run the command unconfined");
    }
}
