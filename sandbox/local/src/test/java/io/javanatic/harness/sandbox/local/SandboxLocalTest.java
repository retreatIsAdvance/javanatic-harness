package io.javanatic.harness.sandbox.local;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.sandbox.sandbox.BackendStatus;
import io.javanatic.harness.sandbox.sandbox.ConfinedArgv;
import io.javanatic.harness.sandbox.sandbox.SandboxEnforcement;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.sandbox.sandbox.WritableRoots;
import io.javanatic.harness.shell.bash.local.BashLocalPlugin;
import io.javanatic.harness.shell.shell.ShellExecutor;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 平台链与 argv 形状的断言<b>平台无关</b>（darwin 上可全跑——it12 的教训：
 * 形状断言挂在真后端 OS 上等于本机之外一行验不到）；真强制 e2e 按平台分挂：
 * 本机 darwin 验 seatbelt、CI ubuntu 验 bwrap（未装 bwrap 的宿主自跳过，
 * CI job 预探保证跳过面在 CI 不可达）。
 */
class SandboxLocalTest {

    @TempDir
    Path workspace;

    private SandboxProvider provider(SandboxLocalPlugin plugin) {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(plugin));
            return rt.root().require(SandboxProvider.KEY);
        }
    }

    /** 借道公开 ShellExecutor seam 跑受限命令（排水/超时语义免费复用）。 */
    private ShellResult run(SandboxProvider provider, String command, SandboxPolicy policy,
                            Path cwd) throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(SandboxProvider.KEY, provider);
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            new PluginLoader().loadAll(rt, List.of(new BashLocalPlugin()));
            ShellExecutor executor = rt.root().require(ShellExecutor.KEY);
            return executor.execute(
                new ShellRequest(command, cwd, Duration.ofSeconds(15), null, policy),
                AbortSignal.never());
        }
    }

    /** bwrap 功能探针（与 provider 同形状，不查 --version）。 */
    private static boolean bwrapUsable() {
        Process probe;
        try {
            probe = new ProcessBuilder("bwrap", "--ro-bind", "/", "/", "--dev", "/dev",
                "--die-with-parent", "--", "true").redirectErrorStream(true).start();
        } catch (IOException spawnFailed) {
            return false;
        }
        try {
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            probe.destroyForcibly();
            return false;
        }
    }

    // ---- 平台链结构（注伪平台，三平台断言平台无关） ----

    @Test
    void platformChainsAreDarwinSeatbeltLinuxBwrapWin32Empty() {
        assertThat(SandboxLocalPlugin.chainFor("darwin")).containsExactly("seatbelt");
        assertThat(SandboxLocalPlugin.chainFor("linux")).containsExactly("bwrap");
        assertThat(SandboxLocalPlugin.chainFor("win32")).isEmpty();
    }

    // ---- argv 形状（纯函数直测，不依赖宿主平台） ----

    @Test
    void seatbeltWrapAdmitsOnlyWritableRootsWithSeatbeltDialect() {
        SandboxPolicy readOnly = new SandboxPolicy(SandboxMode.READ_ONLY, workspace);
        ConfinedArgv ro = SandboxLocalPlugin.seatbeltWrap("/usr/bin/sandbox-exec",
            List.of("bash", "-c", "echo hi"), readOnly);
        assertThat(ro.argv()).first().isEqualTo("/usr/bin/sandbox-exec");
        assertThat(ro.argv()).element(1).isEqualTo("-p");
        String roProfile = ro.argv().get(2);
        assertThat(roProfile).contains("(deny file-write*)").contains("/dev/null");
        assertThat(roProfile).doesNotContain("subpath");
        assertThat(ro.argv()).element(3).isEqualTo("--");
        assertThat(ro.argv().subList(4, ro.argv().size())).containsExactly("bash", "-c", "echo hi");
        assertThat(ro.enforcement()).isEqualTo(SandboxEnforcement.FULL);
        assertThat(ro.denialSignatures()).containsExactly("Operation not permitted");

        SandboxPolicy workspaceWrite = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
        ConfinedArgv ww = SandboxLocalPlugin.seatbeltWrap("/usr/bin/sandbox-exec",
            List.of("bash", "-c", "echo hi"), workspaceWrite);
        String wwProfile = ww.argv().get(2);
        assertThat(WritableRoots.of(workspaceWrite)).isNotEmpty();
        for (Path root : WritableRoots.of(workspaceWrite)) {
            assertThat(wwProfile).contains("(subpath \"" + root + "\")");
        }
    }

    @Test
    void bwrapWrapIsWholeTreeReadOnlyPlusWritableRootsWithBwrapDialect() {
        SandboxPolicy readOnly = new SandboxPolicy(SandboxMode.READ_ONLY, workspace);
        ConfinedArgv ro = SandboxLocalPlugin.bwrapWrap("bwrap",
            List.of("bash", "-c", "echo hi"), readOnly);
        assertThat(ro.argv()).containsExactly(
            "bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--die-with-parent",
            "--", "bash", "-c", "echo hi");
        assertThat(ro.enforcement()).isEqualTo(SandboxEnforcement.FULL);
        assertThat(ro.denialSignatures())
            .containsExactly("Read-only file system", "Permission denied");

        SandboxPolicy workspaceWrite = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
        ConfinedArgv ww = SandboxLocalPlugin.bwrapWrap("bwrap",
            List.of("bash", "-c", "echo hi"), workspaceWrite);
        List<String> profile = ww.argv().subList(1, ww.argv().indexOf("--"));
        assertThat(profile).startsWith("--ro-bind", "/", "/", "--dev", "/dev", "--die-with-parent");
        assertThat(ww.argv()).endsWith("--", "bash", "-c", "echo hi");
        Set<Path> roots = WritableRoots.of(workspaceWrite);
        assertThat(roots).isNotEmpty();
        for (Path root : roots) {
            assertThat(profile).containsSubsequence("--bind", root.toString(), root.toString());
        }
        assertThat(profile.stream().filter("--bind"::equals).count()).isEqualTo(roots.size());
    }

    // ---- fail-closed：空链与候选不可用（注伪平台/二进制，平台无关） ----

    @Test
    void emptyChainFailsClosedNamingPlatformAndPlannedBackend() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("win32", "/usr/bin/sandbox-exec", "bwrap"));
        assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("win32")
            .hasMessageContaining("windows-acl")
            .hasMessageContaining("refusing to run the command unconfined");
    }

    @Test
    void linuxChainFailsClosedWhenBwrapUnusable() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec", "/nonexistent/bwrap"));
        assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("bwrap probe failed (binary: /nonexistent/bwrap)");
    }

    @Test
    void darwinChainFailsClosedWhenSeatbeltUnusable() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("darwin", "/nonexistent/sandbox-exec", "bwrap"));
        assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("/nonexistent/sandbox-exec");
    }

    @Test
    void passthroughPolicyIsRejectedByConfine() {
        SandboxProvider provider = provider(new SandboxLocalPlugin());
        assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("explicit bypass");
    }

    // ---- backendStatus 查询面（与 confine 同一份探针结论） ----

    @Test
    void win32ChainReportsNoBackendNamingPlatform() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("win32", "/usr/bin/sandbox-exec", "bwrap"));
        assertThat(provider.backendStatus())
            .isEqualTo(new BackendStatus.NoBackend("win32"));
    }

    @Test
    void linuxChainReportsProbeFailedNamingBackendBinary() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec", "/nonexistent/bwrap"));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.ProbeFailed.class, failed -> {
                assertThat(failed.platform()).isEqualTo("linux");
                assertThat(failed.detail())
                    .contains("bwrap probe failed (binary: /nonexistent/bwrap)");
            });
    }

    @Test
    void darwinChainReportsProbeFailedWhenSeatbeltUnusable() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("darwin", "/nonexistent/sandbox-exec", "bwrap"));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.ProbeFailed.class, failed -> {
                assertThat(failed.platform()).isEqualTo("darwin");
                assertThat(failed.detail()).contains("/nonexistent/sandbox-exec");
            });
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void realSeatbeltReportsReady() {
        assertThat(provider(new SandboxLocalPlugin()).backendStatus())
            .isEqualTo(new BackendStatus.Ready("seatbelt"));
    }

    // ---- 真强制 e2e：darwin/seatbelt（本机） ----

    @Test
    @EnabledOnOs(OS.MAC)
    void confineWrapsArgvWithoutExecuting() {
        ConfinedArgv confined = provider(new SandboxLocalPlugin()).confine(
            List.of("bash", "-c", "echo hi > hi-proof"),
            new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace));
        assertThat(confined.argv()).first().isEqualTo("/usr/bin/sandbox-exec");
        // confine 不执行命令——workspace 内此刻无文件
        assertThat(Files.exists(workspace.resolve("hi-proof"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void readOnlyDeniesRealWriteWithDialectMarker() throws Exception {
        SandboxProvider provider = provider(new SandboxLocalPlugin());
        ShellResult result = run(provider, "echo denied > out.txt",
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace), workspace);
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("Operation not permitted");
        assertThat(result.sandboxDenied()).isTrue();
        assertThat(Files.exists(workspace.resolve("out.txt"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void workspaceWriteAllowsInsideAndDeniesOutside() throws Exception {
        SandboxProvider provider = provider(new SandboxLocalPlugin());
        SandboxPolicy policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
        ShellResult inside = run(provider, "echo ok > in.txt", policy, workspace);
        assertThat(inside.exitCode()).isZero();
        assertThat(inside.sandboxDenied()).isFalse();
        assertThat(Files.readString(workspace.resolve("in.txt"))).isEqualTo("ok\n");

        // 授予面之外：用户主目录（临时目录族在可写根里，不能用 tmpdir 做反例）
        Path outside = Files.createTempDirectory(Path.of(System.getProperty("user.home")),
            "jh-sandbox-outside");
        try {
            ShellResult denied = run(provider, "echo no > " + outside.resolve("x.txt"),
                policy, workspace);
            assertThat(denied.exitCode()).isNotZero();
            assertThat(denied.sandboxDenied()).isTrue();
            assertThat(Files.exists(outside.resolve("x.txt"))).isFalse();
        } finally {
            Files.deleteIfExists(outside.resolve("x.txt"));
            Files.delete(outside);
        }
    }

    // ---- 真强制 e2e：linux/bwrap（CI ubuntu job；宿主未装 bwrap 自跳过） ----

    @Test
    @EnabledOnOs(OS.LINUX)
    void bwrapReadOnlyDeniesRealWriteWithDialectMarker() throws Exception {
        assumeTrue(bwrapUsable(), "bwrap not usable on this host");
        SandboxProvider provider = provider(new SandboxLocalPlugin());
        ShellResult result = run(provider, "echo denied > out.txt",
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace), workspace);
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("Read-only file system");
        assertThat(result.sandboxDenied()).isTrue();
        assertThat(Files.exists(workspace.resolve("out.txt"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void bwrapWorkspaceWriteAllowsInsideAndDeniesOutside() throws Exception {
        assumeTrue(bwrapUsable(), "bwrap not usable on this host");
        SandboxProvider provider = provider(new SandboxLocalPlugin());
        SandboxPolicy policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
        ShellResult inside = run(provider, "echo ok > in.txt", policy, workspace);
        assertThat(inside.exitCode()).isZero();
        assertThat(inside.sandboxDenied()).isFalse();
        assertThat(Files.readString(workspace.resolve("in.txt"))).isEqualTo("ok\n");

        Path outside = Files.createTempDirectory(Path.of(System.getProperty("user.home")),
            "jh-sandbox-outside");
        try {
            ShellResult denied = run(provider, "echo no > " + outside.resolve("x.txt"),
                policy, workspace);
            assertThat(denied.exitCode()).isNotZero();
            assertThat(denied.sandboxDenied()).isTrue();
            assertThat(Files.exists(outside.resolve("x.txt"))).isFalse();
        } finally {
            Files.deleteIfExists(outside.resolve("x.txt"));
            Files.delete(outside);
        }
    }
}
