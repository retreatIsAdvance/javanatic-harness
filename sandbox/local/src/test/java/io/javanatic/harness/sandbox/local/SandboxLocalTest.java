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
import io.javanatic.harness.shell.local.LocalShellPlugin;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 平台链与 argv 形状的断言<b>平台无关</b>（darwin 上可全跑——it12 的教训：
 * 形状断言挂在真后端 OS 上等于本机之外一行验不到）；真强制 e2e 按平台分挂：
 * 本机 darwin 验 seatbelt、CI ubuntu 验 bwrap 与 landlock 兜底腿（宿主自身
 * 不满足者自跳过，CI job 预探保证跳过面在 CI 不可达）。注入伪助手的仲裁组
 * 按 POSIX 门（写死 {@code /bin/sh} 形态；Windows 上真机制由 WindowsAclTest 覆盖）。
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
            new PluginLoader().loadAll(rt, List.of(new LocalShellPlugin()));
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
    void platformChainsAreDarwinSeatbeltLinuxBwrapThenLandlockWin32WindowsAcl() {
        assertThat(SandboxLocalPlugin.chainFor("darwin")).containsExactly("seatbelt");
        assertThat(SandboxLocalPlugin.chainFor("linux")).containsExactly("bwrap", "landlock");
        assertThat(SandboxLocalPlugin.chainFor("win32")).containsExactly("windows-acl");
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
            // Windows 宿主 root 渲染为反斜杠，产品经 sbplString 转义（\ → \\）后才进 SBPL
            String sbplRoot = root.toString().replace("\\", "\\\\").replace("\"", "\\\"");
            assertThat(wwProfile).contains("(subpath \"" + sbplRoot + "\")");
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

    // ---- landlock 包装（纯函数直测）：助手前缀 + 助手指令 + argv ----

    @Test
    void landlockWrapCarriesHelperThenModeRootsThenArgv() {
        List<String> helper = List.of("/jdk/bin/java", "-XX:-UsePerfData",
            "--enable-native-access=helper", "-cp", "/helper", "helper.Main");
        SandboxPolicy readOnly = new SandboxPolicy(SandboxMode.READ_ONLY, workspace);
        ConfinedArgv ro = SandboxLocalPlugin.landlockWrap(helper,
            List.of("bash", "-c", "echo hi"), readOnly);
        assertThat(ro.argv()).startsWith(helper.toArray(String[]::new));
        assertThat(ro.enforcement()).isEqualTo(SandboxEnforcement.FULL);
        assertThat(ro.denialSignatures()).containsExactly("Permission denied");

        Landlock.Invocation roInvocation = Landlock.parse(
            ro.argv().subList(helper.size(), ro.argv().size())).orElseThrow();
        assertThat(roInvocation.probe()).isFalse();
        assertThat(roInvocation.mode()).isEqualTo(Landlock.MODE_READ_ONLY);
        assertThat(roInvocation.roots()).isEmpty();
        assertThat(roInvocation.argv()).containsExactly("bash", "-c", "echo hi");

        SandboxPolicy workspaceWrite = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
        ConfinedArgv ww = SandboxLocalPlugin.landlockWrap(helper,
            List.of("bash", "-c", "echo hi"), workspaceWrite);
        Landlock.Invocation wwInvocation = Landlock.parse(
            ww.argv().subList(helper.size(), ww.argv().size())).orElseThrow();
        assertThat(wwInvocation.mode()).isEqualTo(Landlock.MODE_WORKSPACE_WRITE);
        assertThat(wwInvocation.roots())
            .containsExactlyInAnyOrderElementsOf(WritableRoots.of(workspaceWrite));
        assertThat(wwInvocation.argv()).containsExactly("bash", "-c", "echo hi");
    }

    @Test
    void landlockWrapRefusesPassthroughModeInsteadOfWrappingIt() {
        assertThatThrownBy(() -> SandboxLocalPlugin.landlockWrap(List.of("/jdk/bin/java"),
            List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("explicit bypass");
    }

    // ---- windows-acl 包装（纯函数直测）：助手前缀 + 助手指令 + argv ----

    @Test
    void windowsAclWrapReadOnlyCarriesNoRootsAndNoTemp() {
        List<String> helper = List.of("/jdk/bin/java", "-XX:-UsePerfData", "WindowsAclExecMain");
        ConfinedArgv ro = SandboxLocalPlugin.windowsAclWrap(helper,
            List.of("cmd.exe", "/d", "/c", "echo hi"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace));
        assertThat(ro.argv()).startsWith(helper.toArray(String[]::new));
        assertThat(ro.enforcement()).isEqualTo(SandboxEnforcement.PARTIAL);
        assertThat(ro.denialSignatures())
            .containsExactlyElementsOf(SandboxLocalPlugin.WINDOWS_ACL_DENIALS);

        WindowsAcl.Invocation invocation = WindowsAcl.parse(
            ro.argv().subList(helper.size(), ro.argv().size())).orElseThrow();
        assertThat(invocation.mode()).isEqualTo(WindowsAcl.MODE_READ_ONLY);
        assertThat(invocation.roots()).isEmpty();
        assertThat(invocation.temp()).isEmpty();
        assertThat(invocation.argv()).containsExactly("cmd.exe", "/d", "/c", "echo hi");
    }

    @Test
    void windowsAclWrapWorkspaceWritePassesWorkspaceRootAndTempParentOnly() {
        List<String> helper = List.of("/jdk/bin/java");
        ConfinedArgv ww = SandboxLocalPlugin.windowsAclWrap(helper,
            List.of("cmd.exe", "/d", "/c", "echo hi"),
            new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace));
        WindowsAcl.Invocation invocation = WindowsAcl.parse(
            ww.argv().subList(helper.size(), ww.argv().size())).orElseThrow();
        assertThat(invocation.mode()).isEqualTo(WindowsAcl.MODE_WORKSPACE_WRITE);
        // 可写根只有 workspace 根——Windows 宿主临时区不整树打标，与 WritableRoots.of 有意不同源
        assertThat(invocation.roots()).containsExactly(workspace);
        assertThat(invocation.temp()).contains(WritableRoots.tempRoot());
    }

    @Test
    void windowsAclWrapRefusesPassthroughModeInsteadOfWrappingIt() {
        assertThatThrownBy(() -> SandboxLocalPlugin.windowsAclWrap(List.of("/jdk/bin/java"),
            List.of("cmd.exe", "/d", "/c", "echo"),
            new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("explicit bypass");
    }

    // ---- fail-closed：空链与候选不可用（注伪平台/二进制，平台无关） ----

    @Test
    void platformWithoutChainFailsClosedNamingIt() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("sunos", "/usr/bin/sandbox-exec", "bwrap"));
        assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("sunos")
            .hasMessageContaining("refusing to run the command unconfined");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void linuxChainFailsClosedWhenAllCandidatesUnusable() {
        // landlock 助手形态不可判定（空 Optional）= 第二候选也不可用——本机事实无关
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap", Optional.empty()));
        assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("bwrap probe failed (binary: /nonexistent/bwrap)")
            .hasMessageContaining("landlock probe failed (no decidable helper launch form")
            .hasMessageContaining("refusing to run the command unconfined");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
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
    void platformWithoutChainReportsNoBackendNamingIt() {
        SandboxProvider provider = provider(
            new SandboxLocalPlugin("sunos", "/usr/bin/sandbox-exec", "bwrap"));
        assertThat(provider.backendStatus())
            .isEqualTo(new BackendStatus.NoBackend("sunos"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void linuxChainReportsProbeFailedNamingBothCandidateLegs() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap", Optional.empty()));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.ProbeFailed.class, failed -> {
                assertThat(failed.platform()).isEqualTo("linux");
                assertThat(failed.detail())
                    .contains("bwrap probe failed (binary: /nonexistent/bwrap)")
                    .contains("landlock probe failed (no decidable helper launch form");
            });
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
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
            .isEqualTo(new BackendStatus.Ready("seatbelt", SandboxEnforcement.FULL, ""));
    }

    // ---- linux 第二候选仲裁（注伪助手：POSIX 宿主上可验 fallback 与诊断回收） ----
    // 注伪助手只替「助手命令」，真结论（ABI/rights/真拒写）由 LandlockTest 与 CI 腿验。
    // Windows 宿主不可跑：探针首腿（bwrap/seatbelt）内部以 Path.of("/") 造哑根，
    // 在 Windows 渲染为 \ 被 SandboxPolicy 拒——产品不可达（chainFor("win32") 无此腿）。

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void linuxChainFallsBackToLandlockWhenBwrapUnusable() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap", Optional.of(List.of("/usr/bin/true"))));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.Ready.class, ready -> {
                assertThat(ready.backend()).isEqualTo("landlock");
                assertThat(ready.enforcement()).isEqualTo(SandboxEnforcement.FULL);
                // 注伪助手无输出 → 细节只有前候选失败（无 "; " 尾段）
                assertThat(ready.detail()).isEqualTo("bwrap probe failed (binary: /nonexistent/bwrap)");
            });
        // 选择期 fallback：confine 走 landlock 腿（注伪助手在前缀，而非 bwrap）
        assertThat(provider.confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)).argv())
            .first().isEqualTo("/usr/bin/true");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void landlockCapabilityLineIsRecoveredIntoReadyDetail() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap", Optional.of(List.of("/bin/sh", "-c",
                "echo booted; echo 'landlock: ready (ABI 3, write denied)'"))));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.Ready.class, ready -> {
                // 结论行取末尾非空行；前候选失败明细与其以 "; " 相连
                assertThat(ready.detail()).isEqualTo("bwrap probe failed (binary: /nonexistent/bwrap)"
                    + "; landlock: ready (ABI 3, write denied)");
            });
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void landlockProbeFailureIsNamedWithExitCodeAndLine() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap", Optional.of(List.of("/bin/sh", "-c",
                "echo 'landlock: ABI too old (no REFER)' 1>&2; exit 11"))));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.ProbeFailed.class, failed ->
                assertThat(failed.detail())
                    .contains("bwrap probe failed (binary: /nonexistent/bwrap)")
                    .contains("landlock probe failed (exit 11 — landlock: ABI too old (no REFER))"));
    }

    // ---- win32 候选仲裁（注伪助手：POSIX 宿主上可验 PARTIAL 回收与失败点名） ----
    // 注伪助手只替「助手命令」，真机制（低完整性令牌/打标/真拒写）由 WindowsAclTest 与 VM 腿验。
    // 注伪助手形态是 /bin/sh -c（POSIX 面）：Windows 宿主上探针无从启动——真机制覆盖面在其
    // 平台的 WindowsAclTest 完整存在，故此处按 POSIX 门。

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void win32ChainReportsReadyWithPartialEnforcementAndCapabilityLine() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("win32", "/usr/bin/sandbox-exec",
            "bwrap", Optional.empty(), Optional.of(List.of("/bin/sh", "-c",
                "echo 'windows-acl: ready — low integrity token; enforcement PARTIAL (stub)'"))));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.Ready.class, ready -> {
                assertThat(ready.backend()).isEqualTo("windows-acl");
                assertThat(ready.enforcement()).isEqualTo(SandboxEnforcement.PARTIAL);
                assertThat(ready.detail())
                    .isEqualTo("windows-acl: ready — low integrity token; enforcement PARTIAL (stub)");
            });
        assertThat(provider.confine(List.of("cmd.exe", "/d", "/c", "echo hi"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)).argv())
            .first().isEqualTo("/bin/sh");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void win32ChainProbeFailureIsNamedWithExitCodeAndLine() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("win32", "/usr/bin/sandbox-exec",
            "bwrap", Optional.empty(), Optional.of(List.of("/bin/sh", "-c",
                "echo 'windows-acl: write to an unlabeled directory was NOT denied' 1>&2; exit 12"))));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.ProbeFailed.class, failed -> {
                assertThat(failed.platform()).isEqualTo("win32");
                assertThat(failed.detail()).contains(
                    "windows-acl probe failed (exit 12 — windows-acl: write to an unlabeled directory"
                        + " was NOT denied)");
            });
        assertThatThrownBy(() -> provider.confine(List.of("cmd.exe", "/d", "/c", "echo"),
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
            .isInstanceOf(SandboxUnavailableException.class)
            .hasMessageContaining("windows-acl probe failed");
    }

    @Test
    void win32ChainWithUndecidableHelperFailsClosed() {
        SandboxProvider provider = provider(new SandboxLocalPlugin("win32", "/usr/bin/sandbox-exec",
            "bwrap", Optional.empty(), Optional.empty()));
        assertThat(provider.backendStatus()).isInstanceOfSatisfying(
            BackendStatus.ProbeFailed.class, failed -> assertThat(failed.detail())
                .contains("windows-acl probe failed (no decidable helper launch form"));
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

    // ---- 真强制 e2e：linux/landlock 兜底腿（CI ubuntu job；无 landlock 内核自跳过） ----
    // 注伪「bwrap 不可用」逼出第二候选；助手指令用真实 hostHelperCommand

    /** 真助手可用性探针（JUnit 侧独立事实，不借被测 provider 的探针结论）。 */
    private static boolean landlockUsable() {
        Optional<List<String>> helper = Landlock.hostHelperCommand();
        if (helper.isEmpty()) {
            return false;
        }
        List<String> probe = new ArrayList<>(helper.get());
        probe.addAll(Landlock.probeArgs());
        Process process;
        try {
            process = new ProcessBuilder(probe).start();
        } catch (IOException spawnFailed) {
            return false;
        }
        try {
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void landlockReadOnlyDeniesRealWriteWithDialectMarker() throws Exception {
        assumeTrue(landlockUsable(), "landlock helper not usable on this host");
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap"));
        ShellResult result = run(provider, "echo denied > out.txt",
            new SandboxPolicy(SandboxMode.READ_ONLY, workspace), workspace);
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("Permission denied");
        assertThat(result.sandboxDenied()).isTrue();
        assertThat(Files.exists(workspace.resolve("out.txt"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void landlockWorkspaceWriteAllowsInsideAndDeniesOutside() throws Exception {
        assumeTrue(landlockUsable(), "landlock helper not usable on this host");
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap"));
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

    // ---- it24 挂账实测（D4）：landlock 腿下 /dev/null 的写 ----
    // 本机 darwin 无 landlock、本机容器内核未编译 landlock ⇒ 事实唯真 landlock 宿主（CI runner）
    // 可出。本项**不带预判**：只断言「腿真在运行（正对照）」+「/dev/null 结局有界（放行或
    // EACCES 拒绝）」，实测分支出 stdout（console + surefire XML system-out）供取证；策略
    // （文档化差异 / 设备节点例外）裁决后再收紧断言。

    @Test
    @EnabledOnOs(OS.LINUX)
    void landlockDevNullWriteIsMeasuredForAdjudication() throws Exception {
        assumeTrue(landlockUsable(), "landlock helper not usable on this host");
        SandboxProvider provider = provider(new SandboxLocalPlugin("linux", "/usr/bin/sandbox-exec",
            "/nonexistent/bwrap"));
        SandboxPolicy policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);

        // 正对照（it24 探针「限前正对照」同口径）：同腿内工作区写成功 ⇒ 腿真在运行，
        // 后续若拒绝才可归因到 /dev/null 本身而非链路失效。
        ShellResult control = run(provider, "echo probe > control.txt", policy, workspace);
        assertThat(control.exitCode()).isZero();
        assertThat(control.sandboxDenied()).isFalse();

        ShellResult result = run(provider, "echo probe > /dev/null", policy, workspace);
        if (result.exitCode() == 0) {
            System.out.println("[D4] landlock leg /dev/null write: ALLOWED (exit 0)");
            assertThat(result.sandboxDenied()).isFalse();
        } else {
            System.out.println("[D4] landlock leg /dev/null write: DENIED (exit " + result.exitCode()
                + ", sandboxDenied=" + result.sandboxDenied() + ", stderr="
                + result.stderr().strip() + ")");
            assertThat(result.sandboxDenied()).isTrue();
            assertThat(result.stderr()).contains("Permission denied");
        }
    }

    // ---- 真强制 e2e：windows-acl（VM/CI windows job） ----
    // 真强制腿在 WindowsAclTest 直跑助手（不经 shell provider——shell-local 的平台分派
    // 归其自身测试，it25 S-b）；此处验链仲裁在真 Windows 把 windows-acl 报成
    // Ready/PARTIAL（探针即机制自检）

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void realWindowsAclReportsReadyWithPartialEnforcement() {
        assertThat(provider(new SandboxLocalPlugin()).backendStatus()).isInstanceOfSatisfying(
            BackendStatus.Ready.class, ready -> {
                assertThat(ready.backend()).isEqualTo("windows-acl");
                assertThat(ready.enforcement()).isEqualTo(SandboxEnforcement.PARTIAL);
                assertThat(ready.detail()).contains("enforcement PARTIAL");
            });
    }

    /**
     * 产品路径真拒写 → 方言命中（S-c 监视项端到端复验面）：命令经 shell 分派（Windows =
     * pwsh）落到原生工具，拒绝文由原生工具按宿主本地化产出——zh-CN 宿主为 GBK 字节，
     * 消费侧解码面不得让 {@code sandboxDenied} 标记落空（S-b leg9b 的六个替换字符即此
     * 风险现场）。授权侧（真写成功）同腿正对照。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAclCommandDeniedOutsideStillMarksSandboxDenied() throws Exception {
        SandboxProvider provider = provider(new SandboxLocalPlugin());
        SandboxPolicy policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace);
        ShellResult inside = run(provider, "cmd.exe /d /c \"echo ok > in.txt\"", policy, workspace);
        assertThat(inside.exitCode()).isZero();
        assertThat(inside.sandboxDenied()).isFalse();
        assertThat(Files.readString(workspace.resolve("in.txt")).strip()).isEqualTo("ok");

        Path outside = Files.createTempDirectory(Path.of(System.getProperty("user.home")),
            "jh-sandbox-outside");
        try {
            ShellResult denied = run(provider,
                "cmd.exe /d /c \"echo no > " + outside.resolve("x.txt") + "\"", policy, workspace);
            assertThat(denied.exitCode()).isNotZero();
            // 诊断留痕（run-2 取证：空 stderr 之谜——确认拒绝文落 stdout 还是 stderr）
            assertThat(denied.sandboxDenied())
                .as("exit=%s stdout=[%s] stderr=[%s]",
                    denied.exitCode(), denied.stdout().strip(), denied.stderr().strip())
                .isTrue();
            assertThat(Files.exists(outside.resolve("x.txt"))).isFalse();
        } finally {
            Files.deleteIfExists(outside.resolve("x.txt"));
            Files.delete(outside);
        }
    }
}
