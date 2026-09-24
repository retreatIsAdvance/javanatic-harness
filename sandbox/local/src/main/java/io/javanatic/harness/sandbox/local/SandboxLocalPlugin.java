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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 本机沙箱 Provider（id "sandbox-local"，dsh sandbox-local 形状）：<b>平台链</b>
 * ——darwin=[seatbelt]、linux=[bwrap, landlock]、win32=[]（windows-acl 设计见
 * 05 §6，实现挂 0.2.0）。候选按序<b>选择期 fallback</b>仲裁（非 per-call 降级）：
 * 一次探针、缓存、进程生命周期内不再切换；空链或候选全不可用一律 fail-closed
 * ——静默透传被禁止。
 *
 * <p>argv 构造是平台无关纯函数（{@link #seatbeltWrap}/{@link #bwrapWrap}/
 * {@link #landlockWrap}）：形状可在任意宿主单测，真强制 e2e 按平台分挂
 * （darwin/macOS job，linux/CI job）。
 */
public final class SandboxLocalPlugin implements Plugin {

    /** darwin 候选：内核强制、无 ABI 降档——FULL 是 profile 事实。 */
    static final String SEATBELT = "seatbelt";

    /** linux 第一候选：外部二进制 + argv 包装，与 seatbelt 形状同构。 */
    static final String BWRAP = "bwrap";

    /** linux 第二候选：JVM 自限制助手（{@link Landlock}）——bwrap 不可用的主机兜底。 */
    static final String LANDLOCK = "landlock";

    /** 平台键 → 候选链表（序即探针序；>1 候选时按序仲裁取首个可用者）。 */
    private static final Map<String, List<String>> PLATFORM_CHAINS = Map.of(
        "darwin", List.of(SEATBELT),
        "linux", List.of(BWRAP, LANDLOCK),
        "win32", List.of());

    private static final String SEPARATOR = "--";

    /** 功能探针上限：二进制挂起视同不可用（fail-closed）。 */
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    /** Seatbelt 的拒绝方言：EPERM（消费方按行内大小写不敏感匹配）。 */
    static final List<String> SEATBELT_DENIALS = List.of("Operation not permitted");

    /** bwrap 的拒绝方言：只读挂载面给 EROFS；可写面内权限拒给 EACCES。 */
    static final List<String> BWRAP_DENIALS = List.of("Read-only file system", "Permission denied");

    /** landlock 的拒绝方言：被 ruleset 拒的写效果给 EACCES（无 EROFS 面）。 */
    static final List<String> LANDLOCK_DENIALS = List.of("Permission denied");

    private final String platform;
    private final String seatbeltBinary;
    private final String bwrapBinary;
    private final Optional<List<String>> landlockHelper;

    /** 数据组合路径：真实平台 + 平台默认二进制（bwrap 走 PATH 解析）。 */
    public SandboxLocalPlugin() {
        this(platform(), "/usr/bin/sandbox-exec", "bwrap");
    }

    /**
     * @param seatbeltBinary darwin 候选二进制路径
     * @param bwrapBinary    linux 第一候选二进制路径（测试注伪探 fail-closed/落兜底）
     */
    public SandboxLocalPlugin(String seatbeltBinary, String bwrapBinary) {
        this(platform(), seatbeltBinary, bwrapBinary);
    }

    /** @param platform 平台键（测试注伪以断言三平台链与空链文案） */
    SandboxLocalPlugin(String platform, String seatbeltBinary, String bwrapBinary) {
        this(platform, seatbeltBinary, bwrapBinary, Landlock.hostHelperCommand());
    }

    /**
     * @param landlockHelper landlock 助手启动命令（测试注伪真助手——darwin 上
     *                       可验仲裁与细节回收；空 = 形态不可判定，fail-closed）
     */
    SandboxLocalPlugin(String platform, String seatbeltBinary, String bwrapBinary,
                       Optional<List<String>> landlockHelper) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.seatbeltBinary = Objects.requireNonNull(seatbeltBinary, "seatbeltBinary");
        this.bwrapBinary = Objects.requireNonNull(bwrapBinary, "bwrapBinary");
        this.landlockHelper = Objects.requireNonNull(landlockHelper, "landlockHelper");
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

    /**
     * landlock 包装：{@code <助手命令> --mode <模式词> [--root <p>]... -- argv}——
     * 助手自限制后 exec argv（{@link Landlock} 类注）。可写根与 seatbelt/bwrap
     * 同源（{@link WritableRoots} 单一来源）。
     */
    static ConfinedArgv landlockWrap(List<String> helperCommand, List<String> argv, SandboxPolicy policy) {
        List<String> wrapped = new ArrayList<>(helperCommand);
        wrapped.addAll(Landlock.runArgs(landlockMode(policy.mode()), WritableRoots.of(policy), argv));
        return new ConfinedArgv(List.copyOf(wrapped), SandboxEnforcement.FULL, LANDLOCK_DENIALS);
    }

    /** 模式词映射：受限档 → 助手指令词表（透传档在 confine 入口已被拒，不可达）。 */
    private static String landlockMode(SandboxMode mode) {
        return switch (mode) {
            case READ_ONLY -> Landlock.MODE_READ_ONLY;
            case WORKSPACE_WRITE -> Landlock.MODE_WORKSPACE_WRITE;
            case DANGER_FULL_ACCESS -> throw new IllegalArgumentException(
                "confine accepts a confining policy; danger-full-access is the caller's explicit bypass");
        };
    }

    /** SBPL 字符串字面量：反斜杠与双引号转义。 */
    private static String sbplString(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 功能探针：有界直跑候选 profile；二进制缺失/不可执行/非零退出/超时皆视同不可用。 */
    private static boolean runProbe(List<String> argv) {
        return runProbeCapturing(argv).usable();
    }

    /** 探针结论：可用性 + 细节（landlock 助手把机制结论写在输出末行）。 */
    private record ProbeOutcome(boolean usable, String detail) {
    }

    /**
     * 有界直跑探针并回收输出（合并 stderr——{@link Landlock} 助手把结论写在
     * stderr）：二进制缺失/非零退出/超时/输出不可读皆视同不可用，detail 即点位。
     */
    private static ProbeOutcome runProbeCapturing(List<String> argv) {
        Process probe;
        try {
            probe = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException spawnFailed) {
            return new ProbeOutcome(false, "did not start (" + spawnFailed.getMessage() + ")");
        }
        try {
            if (!probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return new ProbeOutcome(false, "timed out after " + PROBE_TIMEOUT_SECONDS + "s");
            }
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String detail = lastNonBlankLine(output);
            if (probe.exitValue() != 0) {
                return new ProbeOutcome(false, "exit " + probe.exitValue()
                    + (detail.isEmpty() ? "" : " — " + detail));
            }
            return new ProbeOutcome(true, detail);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            probe.destroyForcibly();
            return new ProbeOutcome(false, "interrupted");
        } catch (IOException outputUnreadable) {
            return new ProbeOutcome(false, "output unreadable (" + outputUnreadable.getMessage() + ")");
        }
    }

    /** 输出末个非空行（探针的结论行恒在末尾；探针输出有界，先等待后读不成环）。 */
    private static String lastNonBlankLine(String output) {
        String last = "";
        for (String line : output.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                last = trimmed;
            }
        }
        return last;
    }

    /** Ready.detail：前候选失败明细 + 本候选能力细节（非空段以 "; " 相连；无段给空串）。 */
    private static String chainDetail(String failures, String capability) {
        if (failures.isEmpty()) {
            return capability;
        }
        return capability.isEmpty() ? failures : failures + "; " + capability;
    }

    /** 候选后端：id + 首探缓存的功能探针 + 本后端 argv 包装 + 诊断面。 */
    private interface Backend {

        String id();

        /** 首探缓存：provider 生命周期内候选可用性不变。 */
        boolean usable();

        /** 首探失败的点名文本（usable() 为 false 后调用；含候选名与探针点位）。 */
        String failureNote();

        /** 首探成功的能力细节（{@link BackendStatus.Ready#detail()}）；无细节空串。 */
        default String capability() {
            return "";
        }

        /** 本后端在此宿主的强制完备度：三个本机后端的入选/探针门槛都保证 FULL。 */
        default SandboxEnforcement enforcement() {
            return SandboxEnforcement.FULL;
        }

        ConfinedArgv confine(List<String> argv, SandboxPolicy policy);
    }

    private final class SeatbeltBackend implements Backend {

        private Boolean usable;

        @Override
        public String id() {
            return SEATBELT;
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
        public String failureNote() {
            return id() + " probe failed (binary: " + seatbeltBinary + ")";
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
        public String failureNote() {
            return id() + " probe failed (binary: " + bwrapBinary + ")";
        }

        @Override
        public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
            return bwrapWrap(bwrapBinary, argv, policy);
        }
    }

    /**
     * landlock 候选（linux 第二，bwrap 不可用主机的兜底）：助手命令由
     * {@link Landlock#hostHelperCommand()} 按运行事实构造（或测试注入）；探针
     * 真跑助手 {@code --probe}（内含正对照 + 真拒写 + 真跑 exec，见 {@link Landlock}），
     * 首探缓存与 bwrap 同形——退出码 >0 一律 fail-closed。
     */
    private final class LandlockBackend implements Backend {

        private Boolean usable;
        private List<String> helper = List.of();
        private String capability = "";
        private String failure = "";

        @Override
        public String id() {
            return LANDLOCK;
        }

        @Override
        public boolean usable() {
            if (usable == null) {
                usable = probe();
            }
            return usable;
        }

        @Override
        public String failureNote() {
            return id() + " probe failed (" + failure + ")";
        }

        @Override
        public String capability() {
            return capability;
        }

        /** 首探：真跑助手 --probe；协议退出码 >0（10/11/12/13/14/127）全为不可用。 */
        private boolean probe() {
            if (landlockHelper.isEmpty()) {
                failure = "no decidable helper launch form (unnamed helper without a classpath)";
                return false;
            }
            List<String> probeArgv = new ArrayList<>(landlockHelper.get());
            probeArgv.addAll(Landlock.probeArgs());
            ProbeOutcome outcome = runProbeCapturing(probeArgv);
            if (outcome.usable()) {
                helper = List.copyOf(landlockHelper.get());
                capability = outcome.detail();
                return true;
            }
            failure = outcome.detail();
            return false;
        }

        @Override
        public ConfinedArgv confine(List<String> argv, SandboxPolicy policy) {
            return landlockWrap(helper, argv, policy);
        }
    }

    /** 链式选择：平台链中首个探针可用者；空链/全不可用一律 fail-closed。 */
    private final class ChainedBackend implements SandboxProvider {

        private final Map<String, Backend> backends = Map.of(
            SEATBELT, new SeatbeltBackend(),
            BWRAP, new BwrapBackend(),
            LANDLOCK, new LandlockBackend());

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
            return backend != null
                ? new BackendStatus.Ready(backend.id(), backend.enforcement(),
                    chainDetail(failures.toString(), backend.capability()))
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
                failures.append(candidate.failureNote());
            }
            return null;
        }
    }
}
