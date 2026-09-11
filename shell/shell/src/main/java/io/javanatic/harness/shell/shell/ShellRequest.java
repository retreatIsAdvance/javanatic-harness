package io.javanatic.harness.shell.shell;

import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 一次命令执行的请求。超时默认集中在此处（30s）——组合或调用方需要不同值时显式传入。
 *
 * @param command shell 命令串（非空；由 provider 决定解释器，bash-local 用 bash -c）
 * @param cwd     工作目录（绝对路径；目录不存在由进程启动 fail loud）
 * @param timeout 超时（null = 30s 默认；到时击杀进程树）
 * @param env     附加环境变量（追加到继承环境之上；null = 无）
 * @param policy  文件效果沙箱策略（必填非 null——受限档经 provider 包装 argv，
 *                透传档是调用方的显式弃权；解析归消费端 resolve 步，08 §7）
 */
public record ShellRequest(String command, Path cwd, Duration timeout, Map<String, String> env,
                           SandboxPolicy policy) {

    /** 超时默认值（唯一默认点，05 §5）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** @throws NullPointerException command/cwd/policy 为 null、IllegalArgumentException 空命令或相对路径时 */
    public ShellRequest {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(policy, "policy");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        if (!cwd.isAbsolute()) {
            throw new IllegalArgumentException("cwd must be absolute: " + cwd);
        }
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
