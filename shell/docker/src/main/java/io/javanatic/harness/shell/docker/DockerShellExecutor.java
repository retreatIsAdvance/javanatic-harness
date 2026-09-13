package io.javanatic.harness.shell.docker;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.shell.shell.ShellExecutor;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 容器内 shell 执行（环境级隔离）。容器按 <b>(规范化 workspace, mode)</b> 键惰性
 * 创建——不同策略绝不共用容器（挂载面不同）；容器名带随机后缀永不按名复用
 * （防跨组合串台）；同键并发共享容器是预期语义。
 *
 * <p><b>击杀链路</b>：docker exec 的 CLI 进程死≠容器内进程死——命令经
 * {@code setsid} 成为会话组长（pid 即 pgid）并把 pid 落盘，超时/取消由主机侧
 * 对 <b>进程组</b>发 TERM 再 KILL（杀整树），CLI destroyForcibly 兜底；击杀
 * exec 有界等待，失败不阻塞收尾（scope close 的 rm -f 终将清场）。模型命令
 * 经环境变量 JH_COMMAND 传输——内容永不进 wrapper 文本，零转义面。
 *
 * <p><b>可写面语义</b>：READ_ONLY → 根只读 + workspace :ro；WORKSPACE_WRITE →
 * 根只读 + workspace :rw + /tmp tmpfs；DANGER → 根可写（容器 OS 隔离仍保留）。
 * pid 文件在 /tmp（各档恒可写的 tmpfs）。
 */
final class DockerShellExecutor implements ShellExecutor {

    /** 容器后端的拒绝方言（挂载面拒绝：EROFS/EACCES）。 */
    static final List<String> DOCKER_DENIALS =
        List.of("Read-only file system", "Permission denied");

    /**
     * 容器内 wrapper（固定文本）：setsid <b>--wait</b> 建会话组并等待回传退出码
     * （fork 模式下 setsid 父进程立即 exit 0——不 --wait 会把失败命令吞成成功）
     * → 记 pid → exec 模型命令。bash 双引号内 {@code \$} 让 $$/$JH_* 在
     * setsid 后的内层 bash 才展开。
     */
    static final String WRAPPER =
        "mkdir -p /tmp/jh-exec && setsid --wait bash -c "
        + "\"echo \\$\\$ > /tmp/jh-exec/\\$JH_EXEC_MARKER.pid; "
        + "exec bash -c \\\"\\$JH_COMMAND\\\"\"";

    private static final long WAIT_SLICE_MS = 20;
    private static final long DRAIN_JOIN_MS = 5000;
    private static final long KILL_WAIT_MS = 5000;

    private final String dockerCli;
    private final String image;
    private final long maxOutputBytes;
    private final Map<String, String> containers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    DockerShellExecutor(String dockerCli, String image, long maxOutputBytes) {
        this.dockerCli = Objects.requireNonNull(dockerCli, "dockerCli");
        this.image = Objects.requireNonNull(image, "image");
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public ShellResult execute(ShellRequest request, AbortSignal signal) throws Exception {
        Objects.requireNonNull(signal, "signal");
        SandboxPolicy policy = request.policy();
        // cwd 检查先于容器创建：容器只挂载 workspace，越界 cwd 早失败
        Path ws = canonical(policy.workspaceRoot());
        Path cwd = canonical(request.cwd());
        if (!cwd.startsWith(ws)) {
            throw new IllegalArgumentException(
                "cwd must be inside the workspace (container mounts only the workspace): "
                    + cwd + " not under " + ws);
        }
        String container = containerFor(policy);
        String marker = UUID.randomUUID().toString().substring(0, 12);

        List<String> argv = new ArrayList<>();
        argv.add(dockerCli);
        argv.add("exec");
        argv.add("-e");
        argv.add("JH_COMMAND=" + request.command());
        argv.add("-e");
        argv.add("JH_EXEC_MARKER=" + marker);
        for (var entry : request.env().entrySet()) {
            argv.add("-e");
            argv.add(entry.getKey() + "=" + entry.getValue());
        }
        argv.add("-w");
        // 规范化路径：容器内无宿主符号链接（darwin /var→/private/var），
        // 挂载与 chdir 必须同用 realpath 拼写
        argv.add(canonical(request.cwd()).toString());
        argv.add(container);
        argv.add("bash");
        argv.add("-c");
        argv.add(WRAPPER);

        Process process = new ProcessBuilder(argv).start();
        StreamDrain stdout = StreamDrain.start(process.getInputStream(), maxOutputBytes);
        StreamDrain stderr = StreamDrain.start(process.getErrorStream(), maxOutputBytes);
        long startNanos = System.nanoTime();
        try {
            signal.onCancel(() -> killInContainer(container, marker, process));
            long deadline = startNanos + request.timeout().toNanos();
            while (process.isAlive()) {
                signal.checkAbort();
                if (System.nanoTime() >= deadline) {
                    killInContainer(container, marker, process);
                    throw new TimeoutException("docker exec timeout after " + request.timeout());
                }
                process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS);
            }
            signal.checkAbort();
        } catch (AbortedException e) {
            killInContainer(container, marker, process);
            throw e;
        } finally {
            stdout.await();
            stderr.await();
        }
        boolean denied = process.exitValue() != 0
            && matchesDialect(stderr.text(), DOCKER_DENIALS);
        return new ShellResult(process.exitValue(), stdout.text(), stderr.text(),
            Duration.ofNanos(System.nanoTime() - startNanos),
            stdout.truncated() || stderr.truncated(), denied);
    }

    /** R3：scope close 时清掉本执行器创建的全部容器（幂等）。 */
    void shutdown() {
        closed = true;
        for (String cid : containers.values()) {
            runBounded(List.of(dockerCli, "rm", "-f", cid), KILL_WAIT_MS);
        }
        containers.clear();
    }

    /** 容器数（create-once 断言用）。 */
    int containerCount() {
        return containers.size();
    }

    private String containerFor(SandboxPolicy policy) {
        if (closed) {
            throw new IllegalStateException("docker executor is shut down");
        }
        String key = canonical(policy.workspaceRoot()) + "::" + policy.mode();
        return containers.computeIfAbsent(key, ignored -> create(policy));
    }

    private String create(SandboxPolicy policy) {
        Path ws = canonical(policy.workspaceRoot());
        List<String> argv = new ArrayList<>(List.of(dockerCli, "run", "-d", "--init",
            "--name", "jh-shell-" + UUID.randomUUID().toString().substring(0, 12),
            "--tmpfs", "/tmp"));
        if (policy.mode() != SandboxMode.DANGER_FULL_ACCESS) {
            argv.add("--read-only");
        }
        argv.add("-v");
        argv.add(ws + ":" + ws + (policy.mode() == SandboxMode.READ_ONLY ? ":ro" : ":rw"));
        argv.add("-w");
        argv.add(ws.toString());
        // --entrypoint sleep：镜像自身的 ENTRYPOINT 不得劫持保活进程
        argv.add("--entrypoint");
        argv.add("sleep");
        argv.add(image);
        argv.add("infinity");
        try {
            Process process = new ProcessBuilder(argv).start();
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new SandboxUnavailableException(policy.mode(),
                    "container create failed: " + err.strip());
            }
            String cid = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).strip();
            if (cid.isEmpty()) {
                throw new SandboxUnavailableException(policy.mode(), "container id empty");
            }
            return cid;
        } catch (SandboxUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxUnavailableException(policy.mode(),
                "container create failed: " + e.getMessage());
        }
    }

    /** realpath 规范化；解析失败保守返回原拼写（与 WritableRoots 同款语义）。 */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missingOrUnreadable) {
            return path;
        }
    }

    /**
     * 容器内进程组击杀：读 pid 文件 → 对 -pgid 发 TERM 再 KILL（杀整树）；
     * 有界等待，失败仅销毁 CLI 不阻塞（rm -f 终将清场）。
     */
    private void killInContainer(String cid, String marker, Process cli) {
        runBounded(List.of(dockerCli, "exec", cid, "bash", "-c",
            "p=$(cat /tmp/jh-exec/" + marker + ".pid 2>/dev/null); "
                + "[ -n \"$p\" ] && kill -TERM -- \"-$p\" 2>/dev/null && sleep 1; "
                + "[ -n \"$p\" ] && kill -KILL -- \"-$p\" 2>/dev/null; true"), KILL_WAIT_MS);
        cli.destroyForcibly();
    }

    private static void runBounded(List<String> argv, long timeoutMs) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            process.destroyForcibly();
        } catch (Exception killIsBestEffort) {
            // 击杀/清理是尽力而为：rm -f 幂等，下一次 shutdown 或手工按前缀清理
        }
    }

    /** 拒绝方言匹配：stderr 逐行大小写不敏感包含任一签名（与 bash-local 同款）。 */
    static boolean matchesDialect(String stderr, List<String> signatures) {
        for (String line : stderr.split("\\R", -1)) {
            String lowered = line.toLowerCase(Locale.ROOT);
            for (String signature : signatures) {
                if (lowered.contains(signature.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 单流排水：上限内缓冲，超限读丢弃，UTF-8 解码（与 LocalBashExecutor 同款）。 */
    static final class StreamDrain {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final InputStream in;
        private final long maxBytes;
        private final Thread thread;
        private volatile boolean truncated;

        private StreamDrain(InputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
            this.thread = Thread.ofVirtual().name("jh-docker-drain").unstarted(this::run);
        }

        static StreamDrain start(InputStream in, long maxBytes) {
            StreamDrain drain = new StreamDrain(in, maxBytes);
            drain.thread.start();
            return drain;
        }

        private void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    long room = maxBytes - buffer.size();
                    if (room > 0) {
                        int take = (int) Math.min(read, room);
                        buffer.write(chunk, 0, take);
                        if (take < read) {
                            truncated = true;
                        }
                    } else if (read > 0) {
                        truncated = true;
                    }
                }
            } catch (IOException processKilled) {
                // 进程被击杀导致的流关闭：残余内容以已缓冲为准,截断标记不回退
            }
        }

        void await() {
            try {
                thread.join(DRAIN_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
