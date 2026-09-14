package io.javanatic.harness.sandbox.local;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.sandbox.sandbox.BackendStatus;
import io.javanatic.harness.sandbox.sandbox.ConfinedArgv;
import io.javanatic.harness.sandbox.sandbox.SandboxEnforcement;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.sandbox.sandbox.SandboxUnavailableException;
import io.javanatic.harness.sandbox.sandbox.WritableRoots;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 本机沙箱 Provider（id "sandbox-local"，dsh sandbox-local 形状）：<b>平台链</b>
 * ——darwin=[seatbelt]、linux=[bwrap]、win32=[]（windows-acl 设计见 05 §6，
 * 实现挂 0.2.0）。单候选平台不做仲裁，只做该候选的<b>功能探针</b>（真建真跑
 * 一个受限 profile，不是查 --version）；空链或候选不可用一律 fail-closed——
 * 静默透传被禁止。
 *
 * <p>argv 构造是平台无关纯函数（{@link #seatbeltWrap}/{@link #bwrapWrap}）：
 * 形状可在任意宿主单测，真强制 e2e 按平台分挂（darwin/macOS job，linux/CI job）。
 */
public final class SandboxLocalPlugin implements Plugin {

    /** darwin 候选：内核强制、无 ABI 降档——FULL 是 profile 事实。 */
    static final String SEATBELT = "seatbelt";

    /** linux 候选：外部二进制 + argv 包装，与 seatbelt 形状同构。 */
    static final String BWRAP = "bwrap";

    /** 平台键 → 候选链表（序即探针序；>1 候选时按序仲裁取首个可用者）。 */
    private static final Map<String, List<String>> PLATFORM_CHAINS = Map.of(
        "darwin", List.of(SEATBELT),
        "linux", List.of(BWRAP),
        "win32", List.of());

    private static final String SEPARATOR = "--";

    /** 功能探针上限：二进制挂起视同不可用（fail-closed）。 */
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    /** Seatbelt 的拒绝方言：EPERM（消费方按行内大小写不敏感匹配）。 */
    static final List<String> SEATBELT_DENIALS = List.of("Operation not permitted");

    /** bwrap 的拒绝方言：只读挂载面给 EROFS；可写面内权限拒给 EACCES。 */
    static final List<String> BWRAP_DENIALS = List.of("Read-only file system", "Permission denied");

    private final String platform;
    private final String seatbeltBinary;
    private final String bwrapBinary;

    /** 数据组合路径：真实平台 + 平台默认二进制（bwrap 走 PATH 解析）。 */
    public SandboxLocalPlugin() {
        this(platform(), "/usr/bin/sandbox-exec", "bwrap");
    }

    /**
     * @param seatbeltBinary darwin 候选二进制路径
     * @param bwrapBinary    linux 候选二进制路径（测试注伪探 fail-closed）
     */
    public SandboxLocalPlugin(String seatbeltBinary, String bwrapBinary) {
        this(platform(), seatbeltBinary, bwrapBinary);
    }

    /** @param platform 平台键（测试注伪以断言三平台链与空链文案） */
    SandboxLocalPlugin(String platform, String seatbeltBinary, String bwrapBinary) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.seatbeltBinary = Objects.requireNonNull(seatbeltBinary, "seatbeltBinary");
        this.bwrapBinary = Objects.requireNonNull(bwrapBinary, "bwrapBinary");
    }

    @Override
    public String id() {
        return "sandbox-local";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(SandboxProvider.KEY, new ChainedBackend());
    }

    /** 平台键：darwin / win32 / linux（非 mac/非 win 归 linux，node 口径）。 */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "darwin";
        }
        if (os.contains("win")) {
            return "win32";
        }
        return "linux";
    }

    /** 平台候选链（空表 = 无同机后端平台）。 */
    static List<String> chainFor(String platform) {
        return PLATFORM_CHAINS.getOrDefault(platform, List.of());
    }

    /** 空链平台 fail-closed 文案：点名平台与计划后端。 */
    static SandboxUnavailableException noBackend(String platform, SandboxMode mode) {
        return new SandboxUnavailableException(mode, "no same-host sandbox backend for platform \""
            + platform + "\" yet" + ("win32".equals(platform) ? " (windows-acl planned — 0.2.0)" : ""));
    }

    // ---- argv 构造（平台无关纯函数；形状断言不依赖宿主平台） ----

    /** Seatbelt 包装：{@code binary -p <profile> -- argv}。 */
    static ConfinedArgv seatbeltWrap(String binary, List<String> argv, SandboxPolicy policy) {
        List<String> wrapped = new ArrayList<>(argv.size() + 4);
        wrapped.add(binary);
        wrapped.add("-p");
        wrapped.add(seatbeltProfile(policy));
        wrapped.add(SEPARATOR);
        wrapped.addAll(argv);
        return new ConfinedArgv(List.copyOf(wrapped), SandboxEnforcement.FULL, SEATBELT_DENIALS);
    }

    /** bwrap 包装：{@code binary <profile args> -- argv}。 */
    static ConfinedArgv bwrapWrap(String binary, List<String> argv, SandboxPolicy policy) {
        List<String> wrapped = new ArrayList<>();
        wrapped.add(binary);
        wrapped.addAll(bwrapProfileArgs(policy));
        wrapped.add(SEPARATOR);
        wrapped.addAll(argv);
        return new ConfinedArgv(List.copyOf(wrapped), SandboxEnforcement.FULL, BWRAP_DENIALS);
    }

    /**
     * bwrap profile：整个根只读 + 新 devtmpfs（/dev/null 可写——seatbelt
     * profile 里那个 literal 的对应物）+ 父死子死（R3）；workspace-write 再按
     * {@link WritableRoots}（单一来源）逐根 {@code --bind}。cwd 不另设
     * {@code --chdir}——继承调用方（executor 的 ProcessBuilder.directory），
     * 与无沙箱执行一致；bwrap 的 userns/mount-ns 隐式建立（非 setuid 路径），
     * 故无须显式 --unshare-user。
     */
    static List<String> bwrapProfileArgs(SandboxPolicy policy) {
        List<String> args = new ArrayList<>(List.of(
            "--ro-bind", "/", "/", "--dev", "/dev", "--die-with-parent"));
        for (Path root : WritableRoots.of(policy)) {
            args.add("--bind");
            args.add(root.toString());
            args.add(root.toString());
        }
        return List.copyOf(args);
    }

    /** SBPL：全默认放行、拒写；workspace-write 再放行可写根（WritableRoots 单一来源）。 */
    static String seatbeltProfile(SandboxPolicy policy) {
        StringBuilder sb = new StringBuilder("(version 1)(allow default)(deny file-write*)"
            + "(allow file-write* (literal " + sbplString("/dev/null") + "))");
        List<Path> roots = List.copyOf(WritableRoots.of(policy));
        if (!roots.isEmpty()) {
            sb.append("(allow file-write* ");
            for (Path root : roots) {
                sb.append("(subpath ").append(sbplString(root.toString())).append(") ");
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** SBPL 字符串字面量：反斜杠与双引号转义。 */
    private static String sbplString(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 功能探针：有界直跑候选 profile；二进制缺失/不可执行/非零退出/超时皆视同不可用。 */
    private static boolean runProbe(List<String> argv) {
        Process probe;
        try {
            probe = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException spawnFailed) {
            return false;
        }
        try {
            return probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            probe.destroyForcibly();
            return false;
        }
    }

    /** 候选后端：id + 二进制 + 首探缓存的功能探针 + 本后端 argv 包装。 */
    private interface Backend {

        String id();

        String binary();

        /** 首探缓存：provider 生命周期内候选可用性不变。 */
        boolean usable();

        ConfinedArgv confine(List<String> argv, SandboxPolicy policy);
    }

    private final class SeatbeltBackend implements Backend {

        private Boolean usable;

        @Override
        public String id() {
            return SEATBELT;
        }

        @Override
        public String binary() {
            return seatbeltBinary;
        }

        @Override
        public boolean usable() {
            if (usable == null) {
                usable = runProbe(List.of(seatbeltBinary, "-p",
                    seatbeltProfile(new SandboxPolicy(SandboxMode.READ_ONLY, Path.of("/"))),
                    SEPARATOR, "/usr/bin/true"));
            }
            return usable;
        }

        @Override
        public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
            return seatbeltWrap(seatbeltBinary, argv, policy);
        }
    }

    private final class BwrapBackend implements Backend {

        private Boolean usable;

        @Override
        public String id() {
            return BWRAP;
        }

        @Override
        public String binary() {
            return bwrapBinary;
        }

        @Override
        public boolean usable() {
            if (usable == null) {
                List<String> probe = new ArrayList<>(List.of(bwrapBinary));
                probe.addAll(bwrapProfileArgs(new SandboxPolicy(SandboxMode.READ_ONLY, Path.of("/"))));
                probe.add(SEPARATOR);
                probe.add("/usr/bin/true");
                usable = runProbe(probe);
            }
            return usable;
        }

        @Override
        public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
            return bwrapWrap(bwrapBinary, argv, policy);
        }
    }

    /** 链式选择：平台链中首个探针可用者；空链/全不可用一律 fail-closed。 */
    private final class ChainedBackend implements SandboxProvider {

        private final Map<String, Backend> backends = Map.of(
            SEATBELT, new SeatbeltBackend(),
            BWRAP, new BwrapBackend());

        @Override
        public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
            Objects.requireNonNull(argv, "argv");
            Objects.requireNonNull(policy, "policy");
            if (!policy.confining()) {
                throw new IllegalArgumentException(
                    "confine accepts a confining policy; danger-full-access is the caller's explicit bypass");
            }
            if (argv.isEmpty() || argv.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("argv must be non-empty and null-free");
            }
            List<String> chain = chainFor(platform);
            if (chain.isEmpty()) {
                throw noBackend(platform, policy.mode());
            }
            StringBuilder failures = new StringBuilder();
            Backend backend = firstUsable(chain, failures);
            if (backend == null) {
                throw new SandboxUnavailableException(policy.mode(), failures.toString());
            }
            return backend.confine(argv, policy);
        }

        @Override
        public BackendStatus backendStatus() {
            List<String> chain = chainFor(platform);
            if (chain.isEmpty()) {
                return new BackendStatus.NoBackend(platform);
            }
            StringBuilder failures = new StringBuilder();
            Backend backend = firstUsable(chain, failures);
            return backend != null ? new BackendStatus.Ready(backend.id())
                : new BackendStatus.ProbeFailed(platform, failures.toString());
        }

        /** 首个探针可用候选；无则把逐候选失败明细写进 failures 并返回 null。 */
        private Backend firstUsable(List<String> chain, StringBuilder failures) {
            for (String id : chain) {
                Backend candidate = Objects.requireNonNull(backends.get(id),
                    "no backend implementation for chain id \"" + id + "\"");
                if (candidate.usable()) {
                    return candidate;
                }
                if (!failures.isEmpty()) {
                    failures.append("; ");
                }
                failures.append(candidate.id()).append(" probe failed (binary: ")
                    .append(candidate.binary()).append(')');
            }
            return null;
        }
    }
}
