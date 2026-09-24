package io.javanatic.harness.sandbox.local;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Landlock 内核面与助手协议（it24）：linux 候选链的第二候选后端——bwrap 不可用
 * 的主机（未安装 / AppArmor 收紧非特权 userns 等）由它兜底，机制是<b>自限制后
 * exec</b>：本类作为助手进程先 {@code prctl(PR_SET_NO_NEW_PRIVS)} +
 * {@code landlock_restrict_self}，再 exec 目标 argv——限制跨 execve 继承，助手
 * 被目标镜像替换，无监督进程残留（也就与调用方<b>同进程树</b>，既有 killTree
 * 收敛机制零改动覆盖）。
 *
 * <p>能力判断以<b>内核事实</b>为准：向 {@code landlock_create_ruleset} 递全部
 * 期望的写效果 rights，内核拒绝（EINVAL）即逐位收窄，接受的最大子集就是本内核
 * 的真实能力——不按版本号推断。入选门槛 = REFER+TRUNCATE 齐备（内核 6.2+，
 * ABI≥3）：缺任一位则 O_TRUNC 能清空只读文件、跨目录 rename/link 不受控，
 * READ_ONLY 承诺被破坏——按「不满足约束时 fail-closed」拒绝入选，只由探针
 * 点名原因（ABI 1–2 的主机由 bwrap 腿覆盖：那些内核默认不禁非特权 userns）。
 *
 * <p>探针是<b>负向</b>的（对齐 bwrap CI 前置门强度）：真建 ruleset + 真限制 +
 * 限制前写正对照（防 DAC 误绿）+ 限制后同一路径写必须被拒 + 以被限身份
 * exec {@code /usr/bin/true} 收尾（真跑，退出码即结论）。读可见性与网络不在
 * handle 集内（与 seatbelt/bwrap 语义对齐：只约束文件效果）。
 *
 * <p>本类在 provider 进程里只走纯函数（协议构造/命令构造），FFM 句柄在内嵌
 * {@code Native} 持有类里惰性初始化——provider 侧不会碰 libc。
 */
final class Landlock {

    private Landlock() {
    }

    // ---- 模式词（助手指令面） ----

    static final String MODE_READ_ONLY = "read-only";
    static final String MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String MODE_FLAG = "--mode";
    private static final String ROOT_FLAG = "--root";
    private static final String PROBE_FLAG = "--probe";
    private static final String SEPARATOR = "--";
    private static final String MAIN_CLASS = LandlockExecMain.class.getName();

    static final String USAGE = "usage: LandlockExecMain --probe | --mode read-only|workspace-write"
        + " [--root <path>]... -- <command> [args...]";

    // ---- 退出码协议（provider 探针与助手共用；>0 一律 fail-closed） ----

    /** 成功（探针：机制可用且真拒写；run：不返回——被 exec 顶替）。 */
    static final int EXIT_OK = 0;
    /** 内核无 landlock（非 Linux / 未编译 / LSM 未启用 / 架构无 syscall 号）。 */
    static final int EXIT_UNAVAILABLE = 10;
    /** rights 子集缺 REFER/TRUNCATE（内核早于 6.2）——不入选。 */
    static final int EXIT_ABI_INSUFFICIENT = 11;
    /** ruleset 建了但写没被拒——机制不可信，拒绝。 */
    static final int EXIT_NOT_ENFORCING = 12;
    /** 限制应用失败（含正对照失败、根缺失、原生调用异常）。 */
    static final int EXIT_APPLY_FAILED = 13;
    /** 助手指令用法错误。 */
    static final int EXIT_USAGE = 14;
    /** run 模式 exec 目标失败（execvp 返回）。 */
    static final int EXIT_EXEC_FAILED = 127;

    // ---- Landlock 访问权位（uapi: include/uapi/linux/landlock.h） ----

    private static final long ACCESS_FS_WRITE_FILE = 1L << 1;
    private static final long ACCESS_FS_REMOVE_DIR = 1L << 4;
    private static final long ACCESS_FS_REMOVE_FILE = 1L << 5;
    private static final long ACCESS_FS_MAKE_CHAR = 1L << 6;
    private static final long ACCESS_FS_MAKE_DIR = 1L << 7;
    private static final long ACCESS_FS_MAKE_REG = 1L << 8;
    private static final long ACCESS_FS_MAKE_SOCK = 1L << 9;
    private static final long ACCESS_FS_MAKE_FIFO = 1L << 10;
    private static final long ACCESS_FS_MAKE_BLOCK = 1L << 11;
    private static final long ACCESS_FS_MAKE_SYM = 1L << 12;

    /** REFER（ABI 2）：跨目录 rename/link——不进 handle 集则 reparent 不受控。 */
    static final long ACCESS_FS_REFER = 1L << 13;

    /** TRUNCATE（ABI 3）：O_TRUNC/truncate——不进 handle 集则只读文件可被清空。 */
    static final long ACCESS_FS_TRUNCATE = 1L << 14;

    /** 写效果基集（ABI 1 即有）：handle 面只含写——读与执行不约束（词表外，与 seatbelt 对齐）。 */
    static final long WRITE_EFFECTS_V1 = ACCESS_FS_WRITE_FILE | ACCESS_FS_REMOVE_DIR | ACCESS_FS_REMOVE_FILE
        | ACCESS_FS_MAKE_CHAR | ACCESS_FS_MAKE_DIR | ACCESS_FS_MAKE_REG | ACCESS_FS_MAKE_SOCK
        | ACCESS_FS_MAKE_FIFO | ACCESS_FS_MAKE_BLOCK | ACCESS_FS_MAKE_SYM;

    /** 入选门槛：基集 + REFER + TRUNCATE 全在内核接受的子集里（ABI≥3 的实质判据）。 */
    static final long FULL_REQUIREMENT = WRITE_EFFECTS_V1 | ACCESS_FS_REFER | ACCESS_FS_TRUNCATE;

    /**
     * 入选判据（纯函数，gate 与测试共用同一份）：REFER+TRUNCATE 缺任一位即拒——
     * 内核 6.2 之前 O_TRUNC 能清空只读文件、跨目录 rename/link 不受控，
     * READ_ONLY 承诺不成立，宁可 fail-closed 等 bwrap 腿。
     *
     * @param acceptedRights 内核接受的最大 rights 子集
     * @return true = 满足 {@link #FULL_REQUIREMENT}（多出的新位不影响）
     */
    static boolean admits(long acceptedRights) {
        return (acceptedRights & FULL_REQUIREMENT) == FULL_REQUIREMENT;
    }

    // ---- 原生调用常量 ----

    private static final long LANDLOCK_CREATE_RULESET_VERSION = 1L;
    private static final long LANDLOCK_RULE_PATH_BENEATH = 1L;
    private static final int PR_SET_NO_NEW_PRIVS = 38;
    private static final int O_PATH = 0x200000;
    private static final int O_CLOEXEC = 0x20000;
    private static final int O_DIRECTORY = 0x10000;
    private static final long RULESET_ATTR_BYTES = 8;
    private static final long PATH_BENEATH_ATTR_BYTES = 12;
    private static final int ENOSYS = 38;
    private static final int EOPNOTSUPP = 95;
    private static final int EINVAL = 22;
    private static final int ENOENT = 2;
    private static final String PROBE_TARGET = "/usr/bin/true";

    // ---- syscall 号（按架构白名单；他者 fail loud） ----

    /** linux syscall 号三元组（x86_64 与 aarch64 的 landlock 号一致）。 */
    record Syscalls(long createRuleset, long addRule, long restrictSelf) {
    }

    /**
     * 架构 → syscall 号白名单。landlock 号只在 x86_64 与 aarch64 经过本仓实跑；
     * 他者（含 32 位）一律 fail loud——不猜号。
     *
     * @param osArch {@code os.arch} 属性值
     * @return 白名单内为 Syscalls；否则 empty（调用方点名架构）
     */
    static Optional<Syscalls> syscallsForArch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "aarch64", "arm64" -> Optional.of(new Syscalls(444, 445, 446));
            default -> Optional.empty();
        };
    }

    /** 本机是否 Linux（landlock 只在 Linux 内核存在）。 */
    static boolean hostIsLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** 不可运行的平台/架构点名文案；可运行返回 empty（探针与 run 共用的第一道门）。 */
    static Optional<String> unsupportedReason() {
        if (!hostIsLinux()) {
            return Optional.of("landlock: unsupported platform (os.name=" + System.getProperty("os.name", "")
                + ") — landlock is linux-only");
        }
        String arch = System.getProperty("os.arch", "");
        if (syscallsForArch(arch).isEmpty()) {
            return Optional.of("landlock: unsupported architecture \"" + arch
                + "\" — syscall numbers defined for x86_64/aarch64 only");
        }
        return Optional.empty();
    }

    // ---- 助手命令与协议（provider 侧的纯函数面） ----

    /**
     * 助手启动命令（裁决增强 1+2）：固定带 {@code -XX:-UsePerfData}（否则 JVM 启动
     * 在 /tmp 落 hsperfdata——READ_ONLY 下是违规写）与 {@code --enable-native-access}
     * （FFM 受限方法授权；named 模块点模块名，unnamed 用 ALL-UNNAMED）。启动形态按
     * 运行事实三选一（非猜测）：classpath 态 → {@code -cp}；模块路径态 →
     * {@code --module-path + -m}；镜像态（两者皆空，模块在镜像内）→ {@code -m}。
     *
     * @param javaHome   JVM 主目录（镜像态 = 镜像根，其 bin/java 即自带 JVM）
     * @param classPath  unnamed 形态的 classpath（唯一需要的条目 = 助手类所在目录/jar）
     * @param modulePath 模块路径态的非空 {@code jdk.module.path}；镜像态为空串
     * @param moduleName LandlockExecMain 的具名模块名；unnamed 时为 null
     * @return 助手命令；形态不自洽（unnamed 无 classpath）时 empty
     */
    static Optional<List<String>> helperCommand(String javaHome, String classPath, String modulePath, String moduleName) {
        List<String> command = new ArrayList<>(List.of(
            Path.of(javaHome, "bin", "java").toString(), "-XX:-UsePerfData"));
        if (moduleName == null) {
            if (classPath == null || classPath.isEmpty()) {
                return Optional.empty();
            }
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(classPath);
            command.add(MAIN_CLASS);
            return Optional.of(List.copyOf(command));
        }
        command.add("--enable-native-access=" + moduleName);
        if (modulePath != null && !modulePath.isEmpty()) {
            command.add("--module-path");
            command.add(modulePath);
        }
        command.add("-m");
        command.add(moduleName + "/" + MAIN_CLASS);
        return Optional.of(List.copyOf(command));
    }

    /**
     * 本进程的助手启动命令（provider 与测试共用的事实收集点）：具名模块走模块面
     * （模块路径态带 {@code jdk.module.path}，镜像态该属性为空、模块在镜像内）；
     * unnamed 走助手类<b>自身 code source</b> 作 classpath——{@code java.class.path}
     * 在 surefire 等宿主里是只有 Class-Path 清单的 booter jar、无真实条目（it24 实探），
     * 拿它当 classpath 会起一个类都找不到的 JVM。
     *
     * @return 助手命令；形态不可判定时 empty（调用方 fail-closed 并点名）
     */
    static Optional<List<String>> hostHelperCommand() {
        Class<?> helper = LandlockExecMain.class;
        String javaHome = System.getProperty("java.home");
        if (helper.getModule().isNamed()) {
            return helperCommand(javaHome, "", System.getProperty("jdk.module.path", ""),
                helper.getModule().getName());
        }
        return codeSourceOf(helper).flatMap(classPath -> helperCommand(javaHome, classPath, "", null));
    }

    /** 助手类的 code source 路径（目录或 jar）；不可判定时 empty。 */
    private static Optional<String> codeSourceOf(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            return source == null ? Optional.empty()
                : Optional.of(Path.of(source.getLocation().toURI()).toString());
        } catch (URISyntaxException | IllegalArgumentException undeterminable) {
            return Optional.empty();
        }
    }

    /** 探针指令（无根、READ_ONLY 语义：hold 全部写效果、零授予）。 */
    static List<String> probeArgs() {
        return List.of(PROBE_FLAG);
    }

    /** run 指令：{@code --mode <m> [--root <p>]... -- <argv>}（路径不经 shell，逐参传递）。 */
    static List<String> runArgs(String mode, Collection<Path> roots, List<String> argv) {
        List<String> args = new ArrayList<>();
        args.add(MODE_FLAG);
        args.add(mode);
        for (Path root : roots) {
            args.add(ROOT_FLAG);
            args.add(root.toString());
        }
        args.add(SEPARATOR);
        args.addAll(argv);
        return List.copyOf(args);
    }

    /** 解析后的助手指令。 */
    record Invocation(String mode, List<Path> roots, List<String> argv, boolean probe) {

        Invocation {
            roots = List.copyOf(roots);
            argv = List.copyOf(argv);
        }
    }

    /**
     * 解析助手指令；任何形态外输入返回 empty（调用方打印 USAGE 退出 EXIT_USAGE）——
     * fail loud 不猜。
     *
     * @param args 助手 argv（不含 JVM 参数）
     * @return 合法指令；非法 empty
     */
    static Optional<Invocation> parse(List<String> args) {
        if (args.size() == 1 && PROBE_FLAG.equals(args.get(0))) {
            return Optional.of(new Invocation(null, List.of(), List.of(), true));
        }
        String mode = null;
        List<Path> roots = new ArrayList<>();
        int index = 0;
        while (index < args.size()) {
            String token = args.get(index);
            if (SEPARATOR.equals(token)) {
                break;
            }
            if (MODE_FLAG.equals(token)) {
                if (mode != null || index + 1 >= args.size()) {
                    return Optional.empty();
                }
                mode = args.get(++index);
                if (!MODE_READ_ONLY.equals(mode) && !MODE_WORKSPACE_WRITE.equals(mode)) {
                    return Optional.empty();
                }
            } else if (ROOT_FLAG.equals(token)) {
                if (index + 1 >= args.size()) {
                    return Optional.empty();
                }
                roots.add(Path.of(args.get(++index)));
            } else {
                return Optional.empty();
            }
            index++;
        }
        if (mode == null || index >= args.size() || index + 1 >= args.size()) {
            return Optional.empty();
        }
        List<String> argv = List.copyOf(args.subList(index + 1, args.size()));
        if (MODE_READ_ONLY.equals(mode) && !roots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Invocation(mode, List.copyOf(roots), argv, false));
    }

    // ---- 探针（负向自检） ----

    /** {@code landlock_create_ruleset} 探测结论：内核接受的最大 rights 子集。 */
    record RightsProbe(OptionalLong accepted, int abi, int errno) {
    }

    /** 前门结论：合格 → rights/abi；不合格 → 退出码（err 已写）。 */
    private record Gate(long rights, int abi, int exit) {

        boolean ok() {
            return exit == EXIT_OK;
        }
    }

    /** 平台/架构门 + rights 子集门（内核事实）的共同前门。 */
    private static Gate gate(PrintStream err) {
        Optional<String> unsupported = unsupportedReason();
        if (unsupported.isPresent()) {
            err.println(unsupported.get());
            return new Gate(0, -1, EXIT_UNAVAILABLE);
        }
        RightsProbe probing = acceptedRights();
        if (probing.accepted().isEmpty()) {
            err.println("landlock: unavailable — " + absentReason(probing));
            return new Gate(0, probing.abi(), EXIT_UNAVAILABLE);
        }
        long rights = probing.accepted().getAsLong();
        if (!admits(rights)) {
            err.println("landlock: ABI v" + probing.abi() + " below requirement — accepted rights 0x"
                + Long.toHexString(rights) + " lack REFER/TRUNCATE (kernel older than 6.2)");
            return new Gate(rights, probing.abi(), EXIT_ABI_INSUFFICIENT);
        }
        return new Gate(rights, probing.abi(), EXIT_OK);
    }

    /**
     * 探针：前门 → 正对照（限制前写必须成功）→ 自限制 → 验真拒写 → 以被限身份
     * exec true（真跑）。返回退出码；只有「机制可用且真拒写且真跑」才会走到 exec。
     *
     * @param err 诊断出口（stderr）
     * @return {@link #EXIT_OK} 只在机制可用且真拒写且真跑时
     */
    static int probe(PrintStream err) {
        Gate gate = gate(err);
        if (!gate.ok()) {
            return gate.exit();
        }
        long rights = gate.rights();
        Path target = probeTarget();
        try {
            Files.createFile(target);
            Files.delete(target);
        } catch (IOException positiveControlFailed) {
            err.println("landlock: probe positive control failed (" + target + " not writable pre-restriction: "
                + positiveControlFailed + ") — a later denial could not be trusted");
            return EXIT_APPLY_FAILED;
        }
        Integer applied = restrict(rights, List.of(), err);
        if (applied != null) {
            return applied;
        }
        try {
            Files.createFile(target);
            Files.deleteIfExists(target);
            err.println("landlock: ruleset accepted but write was NOT denied — refusing to trust this backend");
            return EXIT_NOT_ENFORCING;
        } catch (IOException denied) {
            err.println("landlock: ready — ABI v" + gate.abi() + ", rights 0x" + Long.toHexString(rights)
                + " (REFER+TRUNCATE), write denied (" + denied.getClass().getSimpleName() + ")");
        }
        execTarget(List.of(PROBE_TARGET), err);
        return EXIT_EXEC_FAILED;
    }

    /**
     * run 指令：前门 → 自限制（可写根逐根授予）→ exec 目标。成功<b>不返回</b>
     * （exec 顶替进程）；返回即失败退出码。
     *
     * @param invocation 解析后的 run 指令
     * @param err        诊断出口
     * @return 失败退出码（成功路径被 exec 顶替）
     */
    static int run(Invocation invocation, PrintStream err) {
        Gate gate = gate(err);
        if (!gate.ok()) {
            return gate.exit();
        }
        Integer applied = restrict(gate.rights(), invocation.roots(), err);
        if (applied != null) {
            return applied;
        }
        execTarget(invocation.argv(), err);
        return EXIT_EXEC_FAILED;
    }

    private static String absentReason(RightsProbe probing) {
        if (probing.errno() == -1) {
            return "native call failure (see stderr)";
        }
        if (probing.errno() == ENOSYS) {
            return "landlock syscalls not present (errno ENOSYS)";
        }
        if (probing.errno() == EOPNOTSUPP) {
            return "landlock LSM not enabled (errno EOPNOTSUPP)";
        }
        return "create_ruleset rejected even the minimal rights set (errno " + probing.errno() + ")";
    }

    private static Path probeTarget() {
        return Path.of(System.getProperty("java.io.tmpdir"),
            "jh-landlock-probe-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
    }

    // ---- 限制应用与 exec ----

    /**
     * 把限制加到<b>本进程</b>上：no_new_privs 先于 restrict_self（内核前置条件），
     * 可写根逐根 PATH_BENEATH 授予（README_ONLY 无根 = 全拒）。成功返回 null。
     *
     * <p><b>不可逆</b>：调用后本进程的所有写效果受 ruleset 约束——调用方（助手）
     * 只应随后 exec 目标或退出，不再做文件写。
     *
     * @param rights 已由 {@link #FULL_REQUIREMENT} 判定合格的内核接受集
     * @param roots  可写根（已规范化；缺失即 fail loud——与 bwrap --bind 同口径）
     * @param err    诊断出口
     * @return null = 已限制；否则退出码
     */
    static Integer restrict(long rights, List<Path> roots, PrintStream err) {
        try {
            int noNewPrivs = prctlNoNewPrivs();
            if (noNewPrivs != 0) {
                err.println("landlock: prctl(PR_SET_NO_NEW_PRIVS) failed (errno " + noNewPrivs + ")");
                return EXIT_APPLY_FAILED;
            }
            CallResult created = createRuleset(rights);
            if (created.value() < 0) {
                err.println("landlock: landlock_create_ruleset failed (errno " + created.errno() + ")");
                return EXIT_APPLY_FAILED;
            }
            int rulesetFd = (int) created.value();
            try {
                for (Path root : roots) {
                    Integer failure = addRootRule(rulesetFd, root, rights, err);
                    if (failure != null) {
                        return failure;
                    }
                }
                CallResult restricted = restrictSelf(rulesetFd);
                if (restricted.value() != 0) {
                    err.println("landlock: landlock_restrict_self failed (errno " + restricted.errno() + ")");
                    return EXIT_APPLY_FAILED;
                }
            } finally {
                closeFd(rulesetFd);
            }
            return null;
        } catch (Throwable nativeFailure) {
            err.println("landlock: native call failure: " + nativeFailure);
            return EXIT_APPLY_FAILED;
        }
    }

    private static Integer addRootRule(int rulesetFd, Path root, long rights, PrintStream err) throws Throwable {
        CallResult opened = openDirFd(root);
        if (opened.value() < 0) {
            err.println("landlock: writable root unreadable: " + root + " (errno " + opened.errno()
                + (opened.errno() == ENOENT ? " — missing root is fail-closed, same as bwrap --bind" : "") + ")");
            return EXIT_APPLY_FAILED;
        }
        int rootFd = (int) opened.value();
        try {
            CallResult added = addPathBeneathRule(rulesetFd, rootFd, rights);
            if (added.value() != 0) {
                err.println("landlock: landlock_add_rule failed for " + root + " (errno " + added.errno() + ")");
                return EXIT_APPLY_FAILED;
            }
        } finally {
            closeFd(rootFd);
        }
        return null;
    }

    /**
     * 以本进程镜像替换为目标 argv（execvp）。成功不返回——限制跨 execve 继承，
     * 目标进程即被限进程，且与调用方同进程树。
     *
     * @param argv 目标命令（argv[0] 按 PATH 解析）
     * @param err  失败诊断出口（返回即失败）
     */
    static void execTarget(List<String> argv, PrintStream err) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment file = arena.allocateFrom(argv.get(0));
            MemorySegment pointers = arena.allocate(ValueLayout.ADDRESS, argv.size() + 1L);
            for (int i = 0; i < argv.size(); i++) {
                pointers.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(argv.get(i)));
            }
            pointers.setAtIndex(ValueLayout.ADDRESS, argv.size(), MemorySegment.NULL);
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            int rc = (int) Landlock.Native.EXECVP.invokeExact(capture, file, pointers);
            err.println("landlock: exec failed for \"" + argv.get(0) + "\" (rc " + rc + ", errno "
                + errnoOf(capture) + ")");
        } catch (Throwable execFailure) {
            err.println("landlock: exec call failure: " + execFailure);
        }
    }

    // ---- 原生调用（FFM；只在助手进程被触发） ----

    private record CallResult(long value, int errno) {
    }

    private static int errnoOf(MemorySegment capture) {
        return capture.get(ValueLayout.JAVA_INT, (int) Landlock.Native.ERRNO_OFFSET);
    }

    private static int prctlNoNewPrivs() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            int rc = (int) Landlock.Native.PRCTL.invokeExact(capture, PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L);
            return rc == 0 ? 0 : errnoOf(capture);
        }
    }

    private static CallResult createRuleset(long handled) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            MemorySegment attr = arena.allocate(RULESET_ATTR_BYTES);
            attr.set(ValueLayout.JAVA_LONG, 0, handled);
            long fd = (long) Landlock.Native.SYSCALL.invokeExact(capture, Landlock.Native.syscalls().createRuleset(),
                attr.address(), RULESET_ATTR_BYTES, 0L, 0L, 0L);
            return new CallResult(fd, errnoOf(capture));
        }
    }

    private static CallResult addPathBeneathRule(int rulesetFd, int parentFd, long allowed) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            MemorySegment attr = arena.allocate(PATH_BENEATH_ATTR_BYTES);
            attr.set(ValueLayout.JAVA_LONG, 0, allowed);
            attr.set(ValueLayout.JAVA_INT, 8, parentFd);
            long rc = (long) Landlock.Native.SYSCALL.invokeExact(capture, Landlock.Native.syscalls().addRule(),
                rulesetFd, LANDLOCK_RULE_PATH_BENEATH, attr.address(), 0L, 0L);
            return new CallResult(rc, errnoOf(capture));
        }
    }

    private static CallResult restrictSelf(int rulesetFd) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            long rc = (long) Landlock.Native.SYSCALL.invokeExact(capture, Landlock.Native.syscalls().restrictSelf(),
                rulesetFd, 0L, 0L, 0L, 0L);
            return new CallResult(rc, errnoOf(capture));
        }
    }

    private static CallResult openDirFd(Path root) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            MemorySegment path = arena.allocateFrom(root.toString());
            int fd = (int) Landlock.Native.OPEN.invokeExact(capture, path, O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
            return new CallResult(fd, errnoOf(capture));
        }
    }

    private static void closeFd(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            int ignored = (int) Landlock.Native.CLOSE.invokeExact(fd);
        } catch (Throwable closeFailure) {
            // 关闭失败无补救路径：fd 随进程退出回收，不掩盖后续流程
        }
    }

    /** 内核接受的最大 rights 子集（EINVAL 逐位收窄 TRUNCATE → REFER；内核事实，不猜版本）。 */
    private static RightsProbe acceptedRights() {
        long candidate = WRITE_EFFECTS_V1 | ACCESS_FS_REFER | ACCESS_FS_TRUNCATE;
        int abi = version();
        while (true) {
            CallResult created;
            try {
                created = createRuleset(candidate);
            } catch (Throwable nativeFailure) {
                System.err.println("landlock: native call failure: " + nativeFailure);
                return new RightsProbe(OptionalLong.empty(), abi, -1);
            }
            if (created.value() >= 0) {
                closeFd((int) created.value());
                return new RightsProbe(OptionalLong.of(candidate), abi, 0);
            }
            if (created.errno() != EINVAL) {
                return new RightsProbe(OptionalLong.empty(), abi, created.errno());
            }
            if ((candidate & ACCESS_FS_TRUNCATE) != 0) {
                candidate &= ~ACCESS_FS_TRUNCATE;
                continue;
            }
            if ((candidate & ACCESS_FS_REFER) != 0) {
                candidate &= ~ACCESS_FS_REFER;
                continue;
            }
            return new RightsProbe(OptionalLong.empty(), abi, created.errno());
        }
    }

    /** ABI 版本查询（诊断用信息面；入选判据仍是 rights 子集）。 */
    private static int version() {
        try {
            CallResult queried = queryVersion();
            return queried.value() > 0 ? (int) queried.value() : -1;
        } catch (Throwable nativeFailure) {
            System.err.println("landlock: native call failure: " + nativeFailure);
            return -1;
        }
    }

    private static CallResult queryVersion() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Landlock.Native.CAPTURE_LAYOUT);
            long abi = (long) Landlock.Native.SYSCALL.invokeExact(capture, Landlock.Native.syscalls().createRuleset(),
                MemorySegment.NULL.address(), 0L, LANDLOCK_CREATE_RULESET_VERSION, 0L, 0L);
            return new CallResult(abi, errnoOf(capture));
        }
    }

    /** FFM 句柄持有类：惰性初始化——provider 进程只用纯函数，不碰 libc。 */
    private static final class Native {

        private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
        private static final MemoryLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
        private static final long ERRNO_OFFSET = CAPTURE_LAYOUT.byteOffset(
            MemoryLayout.PathElement.groupElement("errno"));
        private static final Syscalls SYSCALLS = syscallsForArch(System.getProperty("os.arch", ""))
            .orElseThrow(() -> new IllegalStateException("no landlock syscall numbers for os.arch=\""
                + System.getProperty("os.arch", "") + "\""));

        private static final MethodHandle SYSCALL = variadic("syscall",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), 1);
        private static final MethodHandle PRCTL = variadic("prctl",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), 1);
        private static final MethodHandle OPEN = variadic("open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT), 2);
        private static final MethodHandle CLOSE = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle EXECVP = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("execvp").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE_ERRNO);

        private Native() {
        }

        static Syscalls syscalls() {
            return SYSCALLS;
        }

        /** 可变参 C 函数：firstVariadicArg 的索引逐函数不同（syscall/prctl=1，open=2），不套统一规则。 */
        private static MethodHandle variadic(String symbol, FunctionDescriptor descriptor, int firstVariadic) {
            return Linker.nativeLinker().downcallHandle(
                Linker.nativeLinker().defaultLookup().find(symbol).orElseThrow(),
                descriptor, CAPTURE_ERRNO, Linker.Option.firstVariadicArg(firstVariadic));
        }
    }
}
