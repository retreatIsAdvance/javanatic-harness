package io.javanatic.harness.sandbox.local;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.sandbox.sandbox.ConfinedArgv;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.shell.bash.local.BashLocalPlugin;
import io.javanatic.harness.shell.shell.ShellExecutor;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seatbelt 后端：argv 包装形状、真实强制（写拒 EPERM + 方言标记、工作区内放行、
 * 区外拒）、fail-closed（伪二进制/平台无后端）、透传档拒绝进 provider。
 */
class SandboxLocalTest {

    @TempDir
    Path workspace;

    private SandboxProvider provider() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SandboxLocalPlugin()));
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

    @Test
    @EnabledOnOs(OS.MAC)
    void confineWrapsArgvWithSeatbeltProfile() throws Exception {
        ConfinedArgv confined = provider().confine(List.of("bash", "-c", "echo hi"),
            new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, workspace));
        assertThat(confined.argv()).first().isEqualTo("/usr/bin/sandbox-exec");
        assertThat(confined.argv()).element(1).isEqualTo("-p");
        String profile = confined.argv().get(2);
        assertThat(profile).contains("(deny file-write*)").contains("/dev/null");
        assertThat(profile).contains(workspace.toRealPath().toString());
        assertThat(confined.argv()).element(3).isEqualTo("--");
        assertThat(confined.argv().subList(4, confined.argv().size()))
            .containsExactly("bash", "-c", "echo hi");
        assertThat(confined.denialSignatures()).contains("Operation not permitted");
        // confine 不执行命令——workspace 内此刻无文件
        assertThat(Files.exists(workspace.resolve("hi-proof"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void readOnlyDeniesRealWriteWithDialectMarker() throws Exception {
        SandboxProvider provider = provider();
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
        SandboxProvider provider = provider();
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

    @Test
    void failClosedWhenBackendUnusable() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SandboxLocalPlugin("/nonexistent/sandbox-exec")));
            SandboxProvider provider = rt.root().require(SandboxProvider.KEY);
            assertThatThrownBy(() -> provider.confine(List.of("bash", "-c", "echo"),
                new SandboxPolicy(SandboxMode.READ_ONLY, workspace)))
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("refusing to run the command unconfined");
        }
    }

    @Test
    void passthroughPolicyIsRejectedByConfine() {
        assertThatThrownBy(() -> provider().confine(List.of("bash", "-c", "echo"),
            new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, workspace)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("explicit bypass");
    }
}
