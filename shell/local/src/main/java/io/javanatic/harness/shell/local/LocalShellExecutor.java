package io.javanatic.harness.shell.local;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.sandbox.sandbox.ConfinedArgv;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.shell.shell.ShellExecutor;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本机 shell 执行：argv 由 {@link ShellPlatform} 按平台分派（POSIX = bash -c，
 * Windows = pwsh -EncodedCommand），有界直跑。生产语义：
 * <ul>
 *   <li>取消/超时 = 击杀进程树（{@code ProcessHandle.descendants()} 先于本体；
 *       快速退出进程已脱管的孙进程杀不到——真隔离归 sandbox 切片）</li>
 *   <li>stdout/stderr 并发排水并各设上限：超出截断置标记，继续读丢弃——
 *       不排水会写满管道缓冲死锁子进程</li>
 *   <li>取消经 onCancel 即时击杀 + 等待循环轮询 checkAbort 双保险</li>
 * </ul>
 */
final class LocalShellExecutor implements ShellExecutor {

    /** 等待分片：轮询 checkAbort 的兜底粒度。 */
    private static final long WAIT_SLICE_MS = 20;

    /** 排水线程收尾上限：进程死后流关闭，正常瞬时完成。 */
    private static final long DRAIN_JOIN_MS = 5000;

    private final LocalShellOptions options;
    private final SandboxProvider sandbox;
    private final ShellPlatform platform;

    /** @param sandbox 沙箱 provider（null = 组合未提供——受限请求时 fail-closed 抛出） */
    LocalShellExecutor(LocalShellOptions options, SandboxProvider sandbox) {
        this(options, sandbox, ShellPlatform.host());
    }

    /** 测试注伪：对平台 argv 形状断言，免真宿主依赖。 */
    LocalShellExecutor(LocalShellOptions options, SandboxProvider sandbox, ShellPlatform platform) {
        this.options = options;
        this.sandbox = sandbox;
        this.platform = platform;
    }

    @Override
    public ShellResult execute(ShellRequest request, AbortSignal signal) throws Exception {
        Objects.requireNonNull(signal, "signal");
        List<String> argv = new ArrayList<>(platform.argv(request.command()));
        List<String> denialSignatures = List.of();
        if (request.policy().confining()) {
            if (sandbox == null) {
                throw new SandboxUnavailableException(request.policy().mode(),
                    "no sandbox provider composed (compose sandbox-local before shell-local)");
            }
            ConfinedArgv confined = sandbox.confine(argv, request.policy());
            argv = confined.argv();
            denialSignatures = confined.denialSignatures();
        }
        ProcessBuilder builder = new ProcessBuilder(argv)
            .directory(request.cwd().toFile());
        builder.environment().putAll(request.env());
        Process process = builder.start();
        StreamDrain stdout = StreamDrain.start(process.getInputStream(), options.maxOutputBytes());
        StreamDrain stderr = StreamDrain.start(process.getErrorStream(), options.maxOutputBytes());
        long startNanos = System.nanoTime();
        try {
            signal.onCancel(() -> killTree(process));
            long deadline = startNanos + request.timeout().toNanos();
            while (process.isAlive()) {
                signal.checkAbort();
                if (System.nanoTime() >= deadline) {
                    killTree(process);
                    throw new TimeoutException("shell timeout after " + request.timeout());
                }
                process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS);
            }
            signal.checkAbort();
        } catch (AbortedException e) {
            killTree(process);
            throw e;
        } finally {
            stdout.await();
            stderr.await();
        }
        boolean denied = process.exitValue() != 0
            && matchesDialect(stderr.bytes(), denialSignatures,
                dialectCharsets(System.getProperty("native.encoding")));
        return new ShellResult(process.exitValue(), stdout.text(), stderr.text(),
            Duration.ofNanos(System.nanoTime() - startNanos),
            stdout.truncated() || stderr.truncated(), denied);
    }

    /** 拒绝方言匹配：stderr <b>原始字节</b>按候选解码集试解后<b>逐行</b>大小写不敏感
     * 包含任一签名（dsh「within each stderr line」契约——整流 contains 会把无关长行
     * 里的偶现串误标）。候选集含 native 编码：拒绝由原生控制台程序给出，zh-CN GBK
     * 的「拒绝访问」按 UTF-8 解成 U+FFFD，仅 UTF-8 候选必漏标（it25 首轮 VM 实态）。 */
    static boolean matchesDialect(byte[] stderr, List<String> signatures, List<Charset> charsets) {
        for (Charset charset : charsets) {
            for (String line : new String(stderr, charset).split("\\R", -1)) {
                String lowered = line.toLowerCase(java.util.Locale.ROOT);
                for (String signature : signatures) {
                    if (lowered.contains(signature.toLowerCase(java.util.Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 候选解码集：UTF-8 恒定在列；{@code native.encoding}（JDK 18+ 平台原生编码，
     * Windows zh-CN = GBK）可解析且非 UTF-8 时追加。空/空白/未知一律退化 UTF-8。 */
    static List<Charset> dialectCharsets(String nativeEncoding) {
        if (nativeEncoding == null || nativeEncoding.isBlank()) {
            return List.of(StandardCharsets.UTF_8);
        }
        try {
            Charset nativeCharset = Charset.forName(nativeEncoding.trim());
            return nativeCharset.equals(StandardCharsets.UTF_8)
                ? List.of(StandardCharsets.UTF_8)
                : List.of(StandardCharsets.UTF_8, nativeCharset);
        } catch (IllegalArgumentException unknownCharset) {
            return List.of(StandardCharsets.UTF_8);
        }
    }

    /** 击杀进程树：孙进程先于本体（否则父死孙脱管，descendants 不可达）。幂等。 */
    static void killTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.toHandle().destroyForcibly();
    }

    /** 单流排水：上限内缓冲，超限读丢弃（防子进程写阻塞），UTF-8 解码。 */
    private static final class StreamDrain {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final InputStream in;
        private final long maxBytes;
        private final Thread thread;
        private volatile boolean truncated;

        private StreamDrain(InputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
            this.thread = Thread.ofVirtual().name("jh-shell-drain").unstarted(this::run);
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
            } catch (IOException e) {
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

        byte[] bytes() {
            return buffer.toByteArray();
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
