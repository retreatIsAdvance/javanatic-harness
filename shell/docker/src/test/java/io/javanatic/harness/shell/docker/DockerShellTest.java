package io.javanatic.harness.shell.docker;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.shell.shell.ShellExecutor;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * docker Provider：挂载面语义矩阵（区内放行/容器根拒/只读拒/透传档全开）、容器内
 * 进程组击杀（超时与取消两条触发）、并发配对、create-once 与泄漏（R3）、cwd 边界、
 * 探针 fail loud（daemon 不可达 / CLI 不可用 / 镜像缺失）；daemon 缺席自跳过。
 */
class DockerShellTest {

    /**
     * 本网络 docker.io 确实不可达（{@code docker pull ubuntu:24.04} 报
     * registry-1.docker.io 连接超时）——用本机在场的镜像替代：agent-runner:latest
     * 是 Ubuntu 26.04，含 bash 5.3.9 与 setsid，满足文档对 ubuntu:24.04 的同一
     * 能力要求；部署默认拼写仍是 ubuntu:24.04。CI ubuntu job 可达 docker.io，
     * 用 ubuntu:24.04 打同名 tag 预置本镜像（见 ci.yml）。
     */
    private static final String IMAGE = "agent-runner:latest";

    @TempDir
    Path workspace;

    @BeforeAll
    static void cleanOrphanedContainers() throws Exception {
        if (daemonUp()) {
            // 清理历史孤儿（JVM 崩溃可能留下）——泄漏断言只看本次运行
            new ProcessBuilder("sh", "-c",
                "docker ps -aq --filter name=jh-shell- | xargs -r docker rm -f").start()
                .waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static boolean daemonUp() {
        try {
            Process probe = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
            return probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private ShellResult run(ShellExecutor executor, String command, SandboxPolicy policy,
                            Path cwd) throws Exception {
        return executor.execute(new ShellRequest(command, cwd, Duration.ofSeconds(30), null, policy),
            AbortSignal.never());
    }

    /**
     * 纯 bash 进程枚举（不依赖镜像里的 procps）：comm=sleep 且 cmdline 非容器
     * 自己的保活 {@code sleep infinity}（--entrypoint sleep）才算残留。
     */
    private static String probeSleepCount(ShellExecutor executor, SandboxPolicy policy, Path cwd) {
        return runUnchecked(executor, "for d in /proc/[0-9]*; do "
            + "[ \"$(cat \"$d/comm\" 2>/dev/null)\" = sleep ] || continue; "
            + "cmd=$(tr '\\0' ' ' < \"$d/cmdline\" 2>/dev/null); "
            + "[ \"$cmd\" = 'sleep infinity ' ] || echo \"found:$cmd\"; done; echo checked",
            policy, cwd).stdout();
    }

    private static ShellResult runUnchecked(ShellExecutor executor, String command,
                                            SandboxPolicy policy, Path cwd) {
        try {
            return executor.execute(
                new ShellRequest(command, cwd, Duration.ofSeconds(30), null, policy),
                AbortSignal.never());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mountSurfaceMatrixWorkspaceWriteAndReadOnly() throws Exception {
        assumeTrue(daemonUp(), "docker daemon not running");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new DockerShellPlugin(IMAGE)));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            SandboxPolicy ww = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);

            // 区内（挂载 :rw）放行,宿主侧同一卷可见
            ShellResult inside = run(executor, "echo ok > in.txt", ww, workspace);
            assertThat(inside.exitCode()).isZero();
            assertThat(Files.readString(workspace.resolve("in.txt"))).isEqualTo("ok\n");

            // 容器根文件系统（--read-only,挂载面外）拒 + EROFS 方言标记
            ShellResult rootfs = run(executor, "echo x > /etc/blocked.txt", ww, workspace);
            assertThat(rootfs.exitCode()).isNotZero();
            assertThat(rootfs.sandboxDenied()).isTrue();
            assertThat(rootfs.stderr()).contains("Read-only file system");

            // READ_ONLY：workspace 也挂 :ro——区内写同样拒,宿主无文件
            SandboxPolicy ro = new SandboxPolicy(SandboxMode.READ_ONLY, workspace);
            ShellResult denied = run(executor, "echo y > ro.txt", ro, workspace);
            assertThat(denied.exitCode()).isNotZero();
            assertThat(denied.sandboxDenied()).isTrue();
            assertThat(Files.exists(workspace.resolve("ro.txt"))).isFalse();
        }
    }

    /** 透传档：DANGER 不挂 --read-only——容器根可写、区内写照旧落宿主,均不标拒绝。 */
    @Test
    void dangerModeOpensContainerRootAndWorkspace() throws Exception {
        assumeTrue(daemonUp(), "docker daemon not running");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new DockerShellPlugin(IMAGE)));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            SandboxPolicy danger = new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace);

            // 受限档下这同一写入是 EROFS——透传档必须放行（容器 OS 隔离仍保留）
            ShellResult rootfs = run(executor,
                "echo x > /etc/jh-danger.txt && cat /etc/jh-danger.txt", danger, workspace);
            assertThat(rootfs.exitCode()).isZero();
            assertThat(rootfs.stdout()).isEqualTo("x\n");
            assertThat(rootfs.sandboxDenied()).isFalse();

            ShellResult inside = run(executor, "echo y > d.txt", danger, workspace);
            assertThat(inside.exitCode()).isZero();
            assertThat(Files.readString(workspace.resolve("d.txt"))).isEqualTo("y\n");
        }
    }

    /** 击杀链路：超时杀容器内进程组（含后台子进程）,容器复用无残留。 */
    @Test
    void timeoutKillsInContainerProcessGroup() throws Exception {
        assumeTrue(daemonUp(), "docker daemon not running");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new DockerShellPlugin(IMAGE)));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            SandboxPolicy ww = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);

            assertThatThrownBy(() -> executor.execute(
                new ShellRequest("sleep 30 & sleep 30", workspace, Duration.ofSeconds(1), null, ww),
                AbortSignal.never()))
                .isInstanceOf(TimeoutException.class);

            // 组击杀后同一容器内无 sleep 残留（后台子进程同 pgid 一并死）
            assertThat(probeSleepCount(executor, ww, workspace))
                .contains("checked").doesNotContain("found");
        }
    }

    /**
     * 击杀链路的第二条触发：取消（与超时共用 killInContainer）——抛 AbortedException
     * 而非 TimeoutException，容器内进程组同样死透（onCancel 监听覆盖阻塞等待，
     * checkAbort 轮询覆盖等待间隙，两者都走同一击杀）。
     */
    @Test
    void abortKillsInContainerProcessGroup() throws Exception {
        assumeTrue(daemonUp(), "docker daemon not running");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new DockerShellPlugin(IMAGE)));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            SandboxPolicy ww = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
            CancellableSignal signal = new CancellableSignal();
            // 命令在飞时才取消——立即取消会只覆盖「注册即触发」那半边语义
            Thread canceller = Thread.ofVirtual().unstarted(() -> {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                signal.cancel();
            });
            canceller.start();

            assertThatThrownBy(() -> executor.execute(
                new ShellRequest("sleep 30 & sleep 30", workspace, Duration.ofSeconds(60), null, ww),
                signal))
                .isInstanceOf(AbortedException.class);
            canceller.join(5000);

            assertThat(probeSleepCount(executor, ww, workspace))
                .contains("checked").doesNotContain("found");
        }
    }

    /** 并发配对：4 线程各 4 次——结果与调用无串线；create-once：同键容器数恒 1。 */
    @Test
    void concurrencyPairedAndCreateOnce() throws Exception {
        assumeTrue(daemonUp(), "docker daemon not running");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new DockerShellPlugin(IMAGE)));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            SandboxPolicy ww = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<ShellResult>> futures = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    final int thread = t;
                    for (int i = 0; i < 4; i++) {
                        final int call = i;
                        futures.add(pool.submit(() ->
                            run(executor, "echo t" + thread + "-" + call, ww, workspace)));
                    }
                }
                int idx = 0;
                for (int t = 0; t < 4; t++) {
                    for (int i = 0; i < 4; i++) {
                        ShellResult result = futures.get(idx++).get(30, TimeUnit.SECONDS);
                        assertThat(result.stdout()).isEqualTo("t" + t + "-" + i + "\n");
                    }
                }
            }
            // 16 次执行同 (ws, WORKSPACE_WRITE) 键 → 恰一个容器
            assertThat(countContainers()).isEqualTo(1);
        }
    }

    /** R3 泄漏：scope close 后本组合容器清零;不同工作区 = 不同容器。 */
    @Test
    void scopeCloseRemovesContainersAcrossWorkspaces() throws Exception {
        assumeTrue(daemonUp(), "docker daemon not running");
        Path second = Files.createTempDirectory("jh-docker-ws2");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new DockerShellPlugin(IMAGE)));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            run(executor, "echo a > a.txt", new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace),
                workspace);
            run(executor, "echo b > b.txt", new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, second),
                second);
            run(executor, "echo c", new SandboxPolicy(SandboxMode.READ_ONLY, workspace), workspace);
            // (ws1, WW) + (ws2, WW) + (ws1, RO) = 3 个键 = 3 容器
            assertThat(countContainers()).isEqualTo(3);
        }
        // rt.close() 已跑（try-with-resources）——容器清零
        assertThat(countContainers()).isZero();
    }

    /** cwd 边界：容器只挂 workspace——越界 cwd 早失败（直构执行器,cwd 检查先于任何 docker 调用,无需 daemon）。 */
    @Test
    void cwdOutsideWorkspaceFailsLoudWithoutDaemon() {
        ShellExecutor executor = new DockerShellExecutor("docker", IMAGE, 64 * 1024);
        assertThatThrownBy(() -> run(executor, "echo hi",
            new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace),
            Path.of("/").resolve("elsewhere")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inside the workspace");
    }

    /**
     * 探针 fail loud：daemon 不可达。注伪 CLI（像真 daemon 停机那样 {@code version}
     * 非零退出）而<b>不停真 daemon</b>——本机有无关容器在跑,停 daemon 是破坏性动作。
     * 行走 enable 即 apply,探针失败必须冒泡成装载回滚,不得静默降级到本机 bash。
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void daemonUnreachableFailsLoudAtApply(@TempDir Path bin) throws Exception {
        Path fake = bin.resolve("docker");
        Files.writeString(fake, "#!/bin/sh\n"
            + "echo 'Cannot connect to the Docker daemon at unix:///var/run/docker.sock' >&2\n"
            + "exit 1\n");
        assertThat(fake.toFile().setExecutable(true)).isTrue();
        try (Runtime rt = new Runtime()) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new DockerShellPlugin(IMAGE, DockerShellPlugin.DEFAULT_MAX_OUTPUT_BYTES,
                    fake.toString()))))
                .hasMessageContaining("Plugin failed and rolled back: shell-docker")
                .cause()
                .hasMessageContaining("docker daemon unreachable");
        }
    }

    /** 探针 fail loud：CLI 本身不可用（spawn 失败）——与 daemon 停机是两条不同分支。 */
    @Test
    void unusableCliFailsLoudAtApply() {
        try (Runtime rt = new Runtime()) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new DockerShellPlugin(IMAGE, DockerShellPlugin.DEFAULT_MAX_OUTPUT_BYTES,
                    "/nonexistent/docker"))))
                .hasMessageContaining("Plugin failed and rolled back: shell-docker")
                .cause()
                .hasMessageContaining("docker CLI unusable");
        }
    }

    /** 探针 fail loud：镜像缺失（无自动拉取——首调不被网络拖住）。 */
    @Test
    void missingImageFailsLoudAtApply() {
        assumeTrue(daemonUp(), "docker daemon not running");
        try (Runtime rt = new Runtime()) {
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new DockerShellPlugin("definitely/missing:tag"))))
                .hasMessageContaining("Plugin failed and rolled back: shell-docker")
                .cause()
                .hasMessageContaining("image not present");
        }
    }

    /** 方言匹配为行级：单行包含命中;跨行拼接不命中（整流 contains 的误标修复）。 */
    @Test
    void dialectMatchingIsLineScoped() {
        assertThat(DockerShellExecutor.matchesDialect(
            "bash: /etc/x: Read-only file system\n", DockerShellExecutor.DOCKER_DENIALS)).isTrue();
        assertThat(DockerShellExecutor.matchesDialect(
            "line one Read-only file\nsystem continued\n", DockerShellExecutor.DOCKER_DENIALS))
            .isFalse();
        assertThat(DockerShellExecutor.matchesDialect(
            "denied: Permission denied\n", DockerShellExecutor.DOCKER_DENIALS)).isTrue();
        // \R 而非 \n：容器 stderr 的行界可能是 CRLF——行级语义与平台无关（与 bash-local 同款拼写）
        assertThat(DockerShellExecutor.matchesDialect(
            "bash: /etc/x: Read-only file system\r\n", DockerShellExecutor.DOCKER_DENIALS)).isTrue();
        assertThat(DockerShellExecutor.matchesDialect(
            "line one Read-only file\r\nsystem continued\r\n", DockerShellExecutor.DOCKER_DENIALS))
            .isFalse();
    }

    /**
     * 测试内可取消信号：AbortController 在 core/agent-loop——为取消一条命令拉整条
     * 依赖不成比例，就地实现 AbortSignal 的小契约（含「已取消则 onCancel 立即执行」
     * 这条控制器语义）。
     */
    private static final class CancellableSignal implements AbortSignal {

        private final List<Runnable> actions = new ArrayList<>();
        private volatile boolean cancelled;

        @Override
        public void checkAbort() {
            if (cancelled) {
                throw new AbortedException("cancelled by test");
            }
        }

        @Override
        public void onCancel(Runnable action) {
            boolean runNow;
            synchronized (this) {
                runNow = cancelled;
                if (!runNow) {
                    actions.add(action);
                }
            }
            if (runNow) {
                action.run();
            }
        }

        void cancel() {
            List<Runnable> pending;
            synchronized (this) {
                cancelled = true;
                pending = List.copyOf(actions);
                actions.clear();
            }
            pending.forEach(Runnable::run);
        }
    }

    private static int countContainers() throws Exception {
        Process ps = new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=jh-shell-")
            .start();
        String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(15, TimeUnit.SECONDS);
        return out.isBlank() ? 0 : out.strip().split("\n").length;
    }
}
