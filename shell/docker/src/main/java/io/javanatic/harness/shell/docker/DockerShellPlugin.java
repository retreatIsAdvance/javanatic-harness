package io.javanatic.harness.shell.docker;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.shell.shell.ShellExecutor;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * docker 容器执行 Provider（id "shell-docker"，环境级隔离）——ShellExecutor 的
 * 第二个 Provider，与 shell-bash-local 互斥（同 scope 重复 provide 由 kernel
 * fail loud；per-agent scope 挂载覆盖是 it9 合法形态）。
 *
 * <p><b>apply 期双探针 fail loud</b>（行启用即执行意图，最早可解析点）：
 * {@code docker version}（daemon 可达）与 {@code docker image inspect <image>}
 * （镜像在场——不自动拉取，首调不被网络拖住）。daemon 后启/重启窗口内组合
 * 启不动是诚实信号。镜像须含 bash 与 setsid（ubuntu/debian/alpine 均可）。
 */
public final class DockerShellPlugin implements Plugin {

    /** 数据组合路径的文档化默认（256 KiB 单流上限，与 bash-local 一致）。 */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    /** docker CLI 默认拼写（交 PATH 解析）。 */
    private static final String DEFAULT_DOCKER_CLI = "docker";

    private final String explicitImage;
    private final Long explicitMaxOutput;
    private final String dockerCli;

    /** 数据组合路径：image 必配（无默认镜像——部署的运行时是显式选择）。 */
    public DockerShellPlugin() {
        this(null, null, DEFAULT_DOCKER_CLI);
    }

    /** @param image 容器镜像（须含 bash 与 setsid） */
    public DockerShellPlugin(String image) {
        this(image, DEFAULT_MAX_OUTPUT_BYTES, DEFAULT_DOCKER_CLI);
    }

    /**
     * @param image           容器镜像
     * @param maxOutputBytes 单流输出上限
     */
    public DockerShellPlugin(String image, long maxOutputBytes) {
        this(image, maxOutputBytes, DEFAULT_DOCKER_CLI);
    }

    /**
     * @param image           容器镜像（null = 走 config）
     * @param maxOutputBytes 单流输出上限（null = 走 config）
     * @param dockerCli       docker CLI 可执行名或绝对路径（测试注伪探 fail loud，
     *                        与 sandbox-local 的 seatbeltBinary 同款；docker 兼容 CLI 亦可）
     */
    public DockerShellPlugin(String image, Long maxOutputBytes, String dockerCli) {
        this.explicitImage = image;
        this.explicitMaxOutput = maxOutputBytes;
        this.dockerCli = Objects.requireNonNull(dockerCli, "dockerCli");
    }

    @Override
    public String id() {
        return "shell-docker";
    }

    @Override
    public Set<String> requires() {
        return Set.of();
    }

    @Override
    public void apply(Scope scope) {
        String image = explicitImage != null ? explicitImage
            : ConfigValues.requireString(
                scope.require(ConfigService.KEY).configFor(id()), id(), "image");
        long maxOutput = explicitMaxOutput != null ? explicitMaxOutput
            : ConfigValues.longValue(
                scope.require(ConfigService.KEY).configFor(id()), id(),
                "maxOutputBytes", DEFAULT_MAX_OUTPUT_BYTES);
        probeDaemon(dockerCli);
        probeImage(dockerCli, image);
        DockerShellExecutor executor = new DockerShellExecutor(dockerCli, image, maxOutput);
        scope.provide(ShellExecutor.KEY, executor);
        // R3：容器生命周期挂 scope——close 即 rm -f 全部本插件容器
        scope.onClose(executor::shutdown);
    }

    private static void probeDaemon(String dockerCli) {
        try {
            Process probe = new ProcessBuilder(dockerCli, "version", "--format", "{{.Server.Version}}")
                .redirectErrorStream(true).start();
            if (!probe.waitFor(10, TimeUnit.SECONDS) || probe.exitValue() != 0) {
                throw new IllegalStateException(
                    "shell-docker: docker daemon unreachable (" + dockerCli + " version failed) — "
                        + "the row is enabled, so refusing to boot without the executor backend");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                "shell-docker: docker CLI unusable: " + dockerCli + " — " + e.getMessage(), e);
        }
    }

    private static void probeImage(String dockerCli, String image) {
        try {
            Process probe = new ProcessBuilder(dockerCli, "image", "inspect", image)
                .redirectErrorStream(true).start();
            if (!probe.waitFor(10, TimeUnit.SECONDS) || probe.exitValue() != 0) {
                throw new IllegalStateException("shell-docker: image not present locally: "
                    + image + " — " + dockerCli + " pull " + image
                    + " (no auto-pull: first call must not stall on the network)");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("shell-docker: image probe failed: " + e.getMessage(), e);
        }
    }
}
