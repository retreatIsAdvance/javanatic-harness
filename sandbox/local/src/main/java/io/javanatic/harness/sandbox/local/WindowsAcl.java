package io.javanatic.harness.sandbox.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * windows-acl 后端与助手协议（it25 S-a）：win32 候选链的唯一后端，机制 =
 * <b>低完整性令牌 + 逐对象打标</b>（形状由 S-0 真 VM 实探裁定，原始输出见
 * docs/plan/evidence/iteration-25/S-0-windows-acl-shape.txt）。
 *
 * <p>令牌：{@code CreateRestrictedToken(DISABLE_MAX_PRIVILEGE, 0 受限 SID)} +
 * {@code SetTokenInformation(TokenIntegrityLevel, S-1-16-4096)}——子进程以低完整性
 * 启动，写面由强制完整性策略（MAC）管控：写 Low 标签对象放行、写中标签对象一律拒
 * （DACL 已授权的也拒——MAC 不理会 DACL），读取不受限。既定设计的 WRITE_RESTRICTED
 * 形状已证不可用（{@code CREATE_NO_WINDOW} 下受限 SID 子进程必死 0xC0000142；借
 * Administrators 过启动则写限制形同虚设），不得回头再试。
 *
 * <p>可写面 = <b>打标面</b>：标签不向已存在子对象回溯（实测），故打标是逐对象递归
 * 的硬性义务；新对象由 (OI)(CI) 继承覆盖。会话临时区不整根授予——平台临时区是全
 * 宿主共享的 Medium 面，整树打标是越界足迹；改在 {@code --temp} 下取会话私有 Low
 * 目录并把子进程 TEMP/TMP 指向它。
 *
 * <p><b>完备度 PARTIAL（两洞 + 足迹，随探针结论行外显）</b>：洞 1 = 可写面是主机上
 * 一切 Low 标签对象（不止工作区树——同机其它低完整性沙箱的目录/文件亦在其中）；
 * 洞 2 = 打标遍历跟随硬链接（硬链接无 reparse 属性，跳重解析点的守卫拦不住它），
 * 工作区内的硬链接会把 Low 标签打到<b>工作区外</b>的目标对象上，使其进入可写面且
 * 标签持久留下（VM 实测：目标对象标签变 Low、低完整性子进程经别名写入成功）；打标
 * 是持久元数据，0.2 不做还原（足迹登记在案）。读可见性与网络不在约束面（与
 * seatbelt 词表对齐）。
 *
 * <p><b>拓扑不变量</b>：助手 JVM（不受限，仅带低完整性令牌生子）是唯一的子进程
 * 创建路径——令牌构造失败即不生子（fail-closed）；子进程与调用方同进程树
 * （{@code ProcessHandle.descendants()} 可达，平台侧既有的 killTree 零改动覆盖）。
 * 等待为无限，超时/取消由平台侧击杀治理。
 *
 * <p>探针是负向的（对齐 landlock 强度）：正对照（未受限写必须成功，防 DAC 误绿）
 * → 低完整性子进程写未打标目录必须被拒 → 递归打标 → 新对象可写（继承）+ 打标前
 * 既有对象可写（递归义务）→ 退出码原样透传 → 以特权剥离令牌嵌套跑一遍完整
 * workspace-write 会话（标准用户近似：打标走 LABEL 位、不依赖 SeSecurityPrivilege
 * ——S-0 只在管理员（EnableLUA=0）环境实测过；嵌套腿同时断言会话根打标真生效与
 * TEMP 重定向真生效，后者的观测面取子进程报回的 {@code %TEMP%} 值
 * （会话目录随后被设计删除，在目录内找产物恒搜不到））。全部腿通过才报 ready，
 * 任一腿不符一律 fail-closed。
 *
 * <p>本类在 provider 进程里只走纯函数（协议构造/命令构造/引号/SID 字节），FFM 句柄
 * 在内嵌 {@code Native} 持有类里惰性初始化——provider 侧不会碰 Win32 DLL（也才
 * 使得 darwin 上可单测全部纯函数）。
 */
final class WindowsAcl {

    private WindowsAcl() {
    }

    // ---- 模式词与指令面（助手协议） ----

    static final String MODE_READ_ONLY = "read-only";
    static final String MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String MODE_FLAG = "--mode";
    private static final String ROOT_FLAG = "--root";
    private static final String TEMP_FLAG = "--temp";
    private static final String PROBE_FLAG = "--probe";
    private static final String ARGV_B64_FLAG = "--argv-b64";

    static final String USAGE = "usage: WindowsAclExecMain --probe"
        + " | --mode read-only --argv-b64 <base64>"
        + " | --mode workspace-write --temp <dir> [--root <path>]... --argv-b64 <base64>";

    // ---- 退出码协议（probe 由 provider 解读；run 的正常出口是子进程退出码） ----

    /** 成功（探针：机制可用且各腿全过；run 由入口把它交给 System.exit 传子进程退出码）。 */
    static final int EXIT_OK = 0;
    /** 非 Windows 宿主或 Win32 绑定缺失。 */
    static final int EXIT_UNAVAILABLE = 10;
    /** 机制行为与承诺不符（该拒未拒 / 该放未放）——不可信，拒绝。 */
    static final int EXIT_NOT_ENFORCING = 12;
    /** 应用失败（令牌/打标/根解析/原生调用异常）。 */
    static final int EXIT_APPLY_FAILED = 13;
    /** 助手指令用法错误。 */
    static final int EXIT_USAGE = 14;
    /** 子进程未能创建/未在时限内退出（消费方视同执行失败）。 */
    static final int EXIT_EXEC_FAILED = 127;

    // ---- Win32 常量（原型 = Windows SDK 头文件；形参全 long = invokeExact 无隐式加宽） ----

    private static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    private static final int TOKEN_DUPLICATE = 0x0002;
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    private static final int TOKEN_ACCESS = TOKEN_ASSIGN_PRIMARY | TOKEN_DUPLICATE | TOKEN_QUERY
        | TOKEN_ADJUST_DEFAULT;
    private static final int DISABLE_MAX_PRIVILEGE = 0x0001;
    private static final int TOKEN_INTEGRITY_LEVEL = 25;
    private static final int SE_GROUP_INTEGRITY = 0x00000020;
    private static final int SE_FILE_OBJECT = 1;
    private static final int LABEL_SECURITY_INFORMATION = 0x00000010;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int DUPLICATE_SAME_ACCESS = 0x00000002;
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;
    private static final int WAIT_OBJECT_0 = 0;
    /** DWORD 0xFFFFFFFF（无限等待）。 */
    private static final int WAIT_INFINITE = 0xFFFFFFFF;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final int INVALID_FILE_ATTRIBUTES = -1;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;
    private static final int SE_SELF_RELATIVE = 0x8000;
    private static final int SE_SACL_PRESENT = 0x0010;

    // ---- 结构布局（x64；S-0 实探复核过 STARTUPINFO/PROCESS_INFORMATION/SID_AND_ATTRIBUTES） ----

    private static final long STARTUPINFO_W_SIZE = 104;
    private static final long STARTUPINFO_FLAGS_OFFSET = 60;
    private static final long STARTUPINFO_STDIN_OFFSET = 80;
    private static final long STARTUPINFO_STDOUT_OFFSET = 88;
    private static final long STARTUPINFO_STDERR_OFFSET = 96;
    private static final long PROCESS_INFORMATION_SIZE = 24;
    private static final long SID_AND_ATTRIBUTES_SIZE = 16;
    /** 自相对 SD：control 于偏移 2，SACL 偏移字段于 12（读回只用到头部）。 */
    private static final int SD_CONTROL_OFFSET = 2;
    private static final int SD_SACL_OFFSET_FIELD = 12;
    private static final long SD_HEADER_BYTES = 16;

    /** 打标 SDDL：ML 标记 + (OI)(CI) 继承 + NW（对低于者禁写、对高于者放行）。 */
    static final String LOW_LABEL_SDDL = "S:(ML;OICI;NW;;;LW)";

    /** 探针腿的建子上限（探针整体另受 provider 首探上限约束）。 */
    private static final int PROBE_CHILD_TIMEOUT_MS = 5000;
    /** 嵌套会话腿（含一个嵌套 JVM）的建子上限。 */
    private static final int STRIPPED_SESSION_TIMEOUT_MS = 8000;

    /** 子进程未创建/未按时退出（内部哨兵；调用方转 {@link #EXIT_EXEC_FAILED}）。 */
    private static final int SPAWN_FAILED = Integer.MIN_VALUE;

    // ---- 平台门与纯函数面（provider 与测试共用；darwin 上可全跑） ----

    /** windows-acl 只在 Windows 宿主存在（绑定的是 kernel32/advapi32）。 */
    static boolean hostIsWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 不可运行的平台点名文案；可运行返回 empty（探针与 run 共用的第一道门）。 */
    static Optional<String> unsupportedReason() {
        if (!hostIsWindows()) {
            return Optional.of("windows-acl: unsupported platform (os.name=" + System.getProperty("os.name", "")
                + ") — kernel32/advapi32 bindings are windows-only");
        }
        return Optional.empty();
    }

    /**
     * 本进程的助手启动命令（与 {@link Landlock} 共用 {@link HelperLaunch} 的事实收集
     * 与三形态构造）。
     *
     * @return 助手命令；形态不可判定时 empty（调用方 fail-closed 并点名）
     */
    static Optional<List<String>> hostHelperCommand() {
        return HelperLaunch.hostCommand(WindowsAclExecMain.class);
    }

    /** 探针指令（无根、READ_ONLY 语义的机制自检）。 */
    static List<String> probeArgs() {
        return List.of(PROBE_FLAG);
    }

    /**
     * run 指令：{@code --mode <m> [--temp <dir>] [--root <p>]... --argv-b64 <blob>}
     * （目标 argv 经 {@link #encodeArgv} 压成单 token 载体——provider→助手一跳的
     * ProcessBuilder 会吃掉含引号参数，见 {@link #encodeArgv} 的实测注）。
     *
     * @param mode 模式词
     * @param roots workspace-write 的可写根（read-only 须为空）
     * @param temp workspace-write 的会话临时目录父（read-only 传 null）
     * @param argv 目标命令
     * @return 助手指令 argv
     */
    static List<String> runArgs(String mode, Collection<Path> roots, Path temp, List<String> argv) {
        List<String> args = new ArrayList<>();
        args.add(MODE_FLAG);
        args.add(mode);
        if (temp != null) {
            args.add(TEMP_FLAG);
            args.add(temp.toString());
        }
        for (Path root : roots) {
            args.add(ROOT_FLAG);
            args.add(root.toString());
        }
        args.add(ARGV_B64_FLAG);
        args.add(encodeArgv(argv));
        return List.copyOf(args);
    }

    /**
     * argv → 单参 base64 载体：逐元素 {@code [4 字节大端长度][UTF-8 字节]} 串联后整体
     * Base64（blob 的字母表无引号、无空白）。
     *
     * <p>为什么必须编码（it25 探针 P1/P9 实测）：provider→助手一跳的发送方是宿主 JVM 的
     * {@code ProcessBuilder}——含引号参数在 LEGACY 口径下被吃引号劈段、WIN32_SAFE 口径下
     * {@code \"} 字面泄漏（两态互斥失效），只有「无引号无空白」的单 token 两态都保真。
     * 目标 argv 是任意命令串（引号是其常态），故整体编码而非逐参小心引号。
     */
    static String encodeArgv(List<String> argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String arg : argv) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(bytes.length >>> 24);
            out.write(bytes.length >>> 16);
            out.write(bytes.length >>> 8);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * 单参载体 → argv（{@link #encodeArgv} 的逆）。任何结构缺陷返回 empty——
     * 非 base64、长度字段越界、尾部残渣等一律拒绝不猜（调用方 fail loud 打 USAGE）。
     */
    static Optional<List<String>> decodeArgv(String blob) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(blob);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        List<String> argv = new ArrayList<>();
        int index = 0;
        while (index < bytes.length) {
            if (index + 4 > bytes.length) {
                return Optional.empty();
            }
            long length = ((bytes[index] & 0xFFL) << 24) | ((bytes[index + 1] & 0xFFL) << 16)
                | ((bytes[index + 2] & 0xFFL) << 8) | (bytes[index + 3] & 0xFFL);
            index += 4;
            if (length > bytes.length - index) {
                return Optional.empty();
            }
            argv.add(new String(bytes, index, (int) length, StandardCharsets.UTF_8));
            index += (int) length;
        }
        return Optional.of(argv);
    }

    /** 解析后的助手指令。 */
    record Invocation(String mode, List<Path> roots, Optional<Path> temp, List<String> argv, boolean probe) {

        Invocation {
            roots = List.copyOf(roots);
            temp = Optional.ofNullable(temp).orElse(Optional.empty());
            argv = List.copyOf(argv);
        }
    }

    /**
     * 解析助手指令；任何形态外输入返回 empty（调用方打印 USAGE 退出 EXIT_USAGE）——
     * fail loud 不猜。形态纪律：read-only 无根无 temp；workspace-write 恰有一个 temp
     * （会话临时目录的父，接口承诺它承担 TEMP/TMP 重定向）；argv 载体恰出现一次且为
     * 末 token（{@link #decodeArgv} 结构校验，空 argv 无意义即拒绝）。
     *
     * @param args 助手 argv（不含 JVM 参数）
     * @return 合法指令；非法 empty
     */
    static Optional<Invocation> parse(List<String> args) {
        if (args.size() == 1 && PROBE_FLAG.equals(args.get(0))) {
            return Optional.of(new Invocation(null, List.of(), Optional.empty(), List.of(), true));
        }
        String mode = null;
        Path temp = null;
        boolean tempSeen = false;
        List<Path> roots = new ArrayList<>();
        List<String> argv = null;
        int index = 0;
        while (index < args.size()) {
            String token = args.get(index);
            if (ARGV_B64_FLAG.equals(token)) {
                if (index + 2 != args.size()) {
                    return Optional.empty();
                }
                argv = decodeArgv(args.get(index + 1)).orElse(null);
                if (argv == null) {
                    return Optional.empty();
                }
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
            } else if (TEMP_FLAG.equals(token)) {
                if (tempSeen || index + 1 >= args.size()) {
                    return Optional.empty();
                }
                temp = Path.of(args.get(++index));
                tempSeen = true;
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
        if (mode == null || argv == null || argv.isEmpty()) {
            return Optional.empty();
        }
        if (MODE_READ_ONLY.equals(mode) && (tempSeen || !roots.isEmpty())) {
            return Optional.empty();
        }
        if (MODE_WORKSPACE_WRITE.equals(mode) && !tempSeen) {
            return Optional.empty();
        }
        return Optional.of(new Invocation(mode, List.copyOf(roots), Optional.ofNullable(temp), argv, false));
    }

    /**
     * 低完整性 SID（S-1-16-4096）的字节形：rev=1、子权威数 1、标识权威 16、
     * 子权威 0x1000（小端）——纯字节构造，免 AllocateAndInitializeSid/FreeSid 一对
     * （S-0 实测该 SID 为固定常量，无需系统分配）。
     */
    static byte[] integritySid() {
        return new byte[] {1, 1, 0, 0, 0, 0, 0, 16, 0x00, 0x10, 0x00, 0x00};
    }

    /**
     * SID 字节形 → 字符串（{@code S-1-16-4096} 之类；诊断与单测用，不调原生）。
     *
     * @param sid SID 字节（rev、子权威数、6 字节权威、每 4 字节一子权威）
     * @return {@code S-<rev>-<authority>-<sub>...}
     */
    static String sidToString(byte[] sid) {
        long authority = 0;
        for (int i = 2; i < 8; i++) {
            authority = (authority << 8) | (sid[i] & 0xFFL);
        }
        StringBuilder text = new StringBuilder("S-").append(sid[0] & 0xFF).append('-').append(authority);
        int count = sid[1] & 0xFF;
        for (int i = 0; i < count; i++) {
            int offset = 8 + i * 4;
            long sub = (sid[offset] & 0xFFL) | ((sid[offset + 1] & 0xFFL) << 8)
                | ((sid[offset + 2] & 0xFFL) << 16) | ((sid[offset + 3] & 0xFFL) << 24);
            text.append('-').append(sub);
        }
        return text.toString();
    }

    /**
     * argv → CreateProcessW 命令行（<b>cmd 口径</b>）：空串/含空白/含双引号的参数加
     * 双引号包裹，其余原样；<b>有意不做 MSVCRT 反斜杠转义</b>。
     *
     * <p>命令串跨两跳：provider→助手一跳归 {@link #encodeArgv} 的单参 base64 载体
     * （实测该跳的 ProcessBuilder 吃引号参数，两态互斥失效）；本层只负责助手→目标这一跳，
     * 接收方是 cmd.exe，它不认反斜杠转义（{@code \"} 会被当字面反斜杠），而它剥外壳引号
     * 的规则（无 /S、首字符为引号即去首尾引号）正好吃掉本层包裹、原样保留内层引号——
     * 故本层「只包裹不转义」才是把命令串完整交给 cmd 的形状。
     *
     * <p>边界：参数含内嵌引号且目标不是 cmd 而是 MSVCRT 程序时 argv 保真不成立
     * （Windows 无通用口径，与 ProcessBuilder 同界）；Windows 路径不含引号字符，
     * 命令串里的引号均来自调用方语义。
     *
     * @param argv 目标命令
     * @return 命令行串（逐参包裹，空格分隔）
     */
    static String commandLine(List<String> argv) {
        StringBuilder line = new StringBuilder();
        for (String arg : argv) {
            if (line.length() > 0) {
                line.append(' ');
            }
            if (needsQuoting(arg)) {
                line.append('"').append(arg).append('"');
            } else {
                line.append(arg);
            }
        }
        return line.toString();
    }

    /** 参数是否需引号（cmd 口径）：空串、含空白或含双引号。 */
    private static boolean needsQuoting(String arg) {
        return arg.isEmpty() || arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0 || arg.indexOf('"') >= 0;
    }

    /** 命令串内的路径（cmd 口径）：含空白则加双引号（Windows 路径不含引号字符，无须转义）。 */
    private static String commandQuoted(Path path) {
        String text = path.toString();
        return text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0 ? "\"" + text + "\"" : text;
    }

    // ---- 探针（负向自检） ----

    /**
     * 探针：平台门 → 三处临时树（自检树 + 嵌套会话的根与临时父）→ 各腿（见类注）。
     *
     * @param err 诊断出口（stderr；末行恒为结论行，provider 首探回收作能力细节）
     * @return {@link #EXIT_OK} 只在全部腿通过时
     */
    static int probe(PrintStream err) {
        Optional<String> unsupported = unsupportedReason();
        if (unsupported.isPresent()) {
            err.println(unsupported.get());
            return EXIT_UNAVAILABLE;
        }
        Path root = createProbeDir("jh-winacl-probe-", err);
        Path nestedRoot = createProbeDir("jh-winacl-nested-", err);
        Path nestedTemp = createProbeDir("jh-winacl-temp-", err);
        try {
            if (root == null || nestedRoot == null || nestedTemp == null) {
                return EXIT_APPLY_FAILED;
            }
            return probeLegs(root, nestedRoot, nestedTemp, err);
        } catch (ExceptionInInitializerError bindingFailure) {
            err.println("windows-acl: native bindings unavailable — " + bindingFailure.getCause());
            return EXIT_UNAVAILABLE;
        } catch (IOException probeFailure) {
            err.println("windows-acl: probe failed: " + probeFailure);
            return EXIT_APPLY_FAILED;
        } catch (Throwable nativeFailure) {
            err.println("windows-acl: probe native failure: " + nativeFailure);
            return EXIT_APPLY_FAILED;
        } finally {
            deleteTree(root);
            deleteTree(nestedRoot);
            deleteTree(nestedTemp);
        }
    }

    /**
     * 探针腿序列（腿语义见类注）。任一处不成立即返回对应退出码——不因「大部分腿通过」
     * 而放行。
     *
     * @param root       自检树（打标对象；腿在其内造未打标/已打标/既有对象）
     * @param nestedRoot 嵌套会话的 workspace 根（Medium 新目录——须由嵌套助手真打标才可写）
     * @param nestedTemp 嵌套会话的 --temp 父（同上）
     */
    private static int probeLegs(Path root, Path nestedRoot, Path nestedTemp, PrintStream err) throws IOException {
        Path denyDir = root.resolve("deny");
        Path preExisting = root.resolve("pre.txt");
        Files.createDirectory(denyDir);
        Files.createFile(preExisting);
        Files.writeString(root.resolve("positive.txt"), "ctrl\n");

        long token = lowIntegrityToken(err);
        if (token == 0) {
            return EXIT_APPLY_FAILED;
        }
        try {
            // 正对照的等价物：上面三处 Files.* 写成功即「树本身可写」；下一条腿拒的是
            // 低完整性子进程，不是 DAC——两者都在，才排得掉误绿
            Path denied = denyDir.resolve("denied.txt");
            int deniedExit = spawnLow(token, cmdEcho(denied), null, PROBE_CHILD_TIMEOUT_MS, err);
            if (deniedExit == SPAWN_FAILED) {
                err.println("windows-acl: low-integrity child not creatable — token shape unusable");
                return EXIT_EXEC_FAILED;
            }
            if (Files.exists(denied)) {
                err.println("windows-acl: write to an unlabeled directory was NOT denied (exit=" + deniedExit
                    + ") — refusing to trust this backend（探针树若自身带 Low 标签亦会走到这里）");
                return EXIT_NOT_ENFORCING;
            }
            if (!labelTree(root, err)) {
                return EXIT_APPLY_FAILED;
            }
            Path inherited = denyDir.resolve("inherited.txt");
            int inheritedExit = spawnLow(token, cmdEcho(inherited), null, PROBE_CHILD_TIMEOUT_MS, err);
            if (inheritedExit != 0 || !Files.exists(inherited)) {
                err.println("windows-acl: labeled directory not writable for the low-integrity child (exit="
                    + inheritedExit + ") — labeling does not admit the intended surface");
                return EXIT_NOT_ENFORCING;
            }
            int preExit = spawnLow(token, cmdEcho(preExisting), null, PROBE_CHILD_TIMEOUT_MS, err);
            long preSize = Files.size(preExisting);
            if (preExit != 0 || preSize == 0) {
                err.println("windows-acl: pre-existing object not writable after recursive labeling (exit="
                    + preExit + ", size=" + preSize + ") — recursive labeling ineffective");
                return EXIT_NOT_ENFORCING;
            }
            int exitLeg = spawnLow(token, List.of("cmd.exe", "/d", "/c", "exit", "7"), null,
                PROBE_CHILD_TIMEOUT_MS, err);
            if (exitLeg != 7) {
                err.println("windows-acl: child exit code not propagated (expected 7, got " + exitLeg + ")");
                return EXIT_NOT_ENFORCING;
            }
            if (!strippedSessionLeg(nestedRoot, nestedTemp, err)) {
                return EXIT_NOT_ENFORCING;
            }
        } finally {
            closeHandle(token);
        }
        err.println("windows-acl: ready — low integrity token (S-1-16-4096) + recursive per-object labels;"
            + " enforcement PARTIAL (writable surface = every low-labeled object on this host;"
            + " labeling follows hardlinks, so an object linked into the tree is labeled too, even outside it;"
            + " labels persist, no revert in 0.2)");
        return EXIT_OK;
    }

    /**
     * 标准用户近似腿：以 DISABLE_MAX_PRIVILEGE（剥 SeSecurityPrivilege 等特权、不降
     * 完整性）令牌嵌套跑一遍完整 workspace-write 会话——打标走 LABEL 位，该位存在的
     * 意义即不依赖 SeSecurityPrivilege；S-0 只在管理员（EnableLUA=0）环境实测过，本腿
     * 把「普通用户会话可用」变成可重复验证的断言。嵌套腿同时断言两件事：会话根真被
     * 打标（低完整性子进程真写进根）与 TEMP 重定向真生效。后者的观测面只能落在会话根
     * ——嵌套助手退出时按设计删掉自己的会话临时目录，父探针回头搜该目录内的产物恒搜不到
     * （S-a 首跑实测踩中：原断言写成搜 %TEMP% 产物，真跑必红）。故子进程先把 %TEMP% 的值
     * 记在会话根，再把「往 %TEMP% 写文件」排在命令串<b>最后一条</b>——退出码即它的成败；
     * 两条合起来才成立：记下的值落在 --temp 父下的 jh-sbx-* 目录里（重定向真换向）+ 退出码
     * 0（该目录真可写）。
     *
     * <p>嵌套助手由本助手按自身启动命令重启（{@link #hostHelperCommand()}）——与产品
     * 消费路径同一形状（provider 怎么起我，我怎么起它）。
     */
    private static boolean strippedSessionLeg(Path nestedRoot, Path nestedTemp, PrintStream err) throws IOException {
        Optional<List<String>> selfLaunch = WindowsAcl.hostHelperCommand();
        if (selfLaunch.isEmpty()) {
            err.println("windows-acl: own launch form undecidable — stripped-privilege leg cannot run");
            return false;
        }
        long token = strippedPrivilegeToken(err);
        if (token == 0) {
            return false;
        }
        try {
            Path target = nestedRoot.resolve("nested.txt");
            Path tempSeen = nestedRoot.resolve("temp-seen.txt");
            List<String> nested = new ArrayList<>(selfLaunch.get());
            nested.addAll(runArgs(MODE_WORKSPACE_WRITE, List.of(nestedRoot), nestedTemp,
                List.of("cmd.exe", "/d", "/c", "echo nested > " + commandQuoted(target)
                    + " & echo %TEMP% > " + commandQuoted(tempSeen)
                    + " & echo temp > \"%TEMP%\\temp-proof.txt\"")));
            int exit = spawnLow(token, nested, null, STRIPPED_SESSION_TIMEOUT_MS, err);
            if (exit != 0) {
                err.println("windows-acl: stripped-privilege nested session failed (exit=" + exit + ")");
                return false;
            }
            if (!Files.exists(target)) {
                err.println("windows-acl: nested session did not label its root (no " + target + ")");
                return false;
            }
            if (!Files.exists(tempSeen)) {
                err.println("windows-acl: nested session did not record its TEMP (no " + tempSeen + ")");
                return false;
            }
            Path seen = Path.of(Files.readString(tempSeen).strip());
            if (!seen.startsWith(nestedTemp.toRealPath())
                || !seen.getFileName().toString().startsWith("jh-sbx-")) {
                err.println("windows-acl: nested session TEMP redirect ineffective (child saw " + seen
                    + ", expected a jh-sbx-* dir under " + nestedTemp + ")");
                return false;
            }
            return true;
        } finally {
            closeHandle(token);
        }
    }

    /** 探针临时目录（默认临时区、低完整性机制未启用前的 Medium 面）；失败 null。 */
    private static Path createProbeDir(String prefix, PrintStream err) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException notCreated) {
            err.println("windows-acl: probe temp dir not creatable (" + prefix + "): " + notCreated);
            return null;
        }
    }

    /** 探针腿的命令串：{@code echo x > <路径>}（路径按 cmd 口径引号，见 {@link #commandQuoted}）。 */
    private static List<String> cmdEcho(Path target) {
        return List.of("cmd.exe", "/d", "/c", "echo x > " + commandQuoted(target));
    }

    // ---- run（受限执行） ----

    /**
     * run 指令：平台门 → 低完整性令牌 → workspace-write 时逐根规范化并递归打标 +
     * 建会话临时目录（read-only 无根无 temp，不写任何东西）→ 以令牌生子并等待 →
     * 子进程退出码原样返回。任一步失败即返回对应退出码且<b>不生子</b>（fail-closed）。
     *
     * @param invocation 解析后的 run 指令
     * @param err        诊断出口
     * @return 子进程退出码；失败为 {@link #EXIT_UNAVAILABLE}/{@link #EXIT_APPLY_FAILED}/
     *         {@link #EXIT_EXEC_FAILED}
     */
    static int run(Invocation invocation, PrintStream err) {
        Optional<String> unsupported = unsupportedReason();
        if (unsupported.isPresent()) {
            err.println(unsupported.get());
            return EXIT_UNAVAILABLE;
        }
        long token = 0;
        Path sessionTemp = null;
        try {
            token = lowIntegrityToken(err);
            if (token == 0) {
                return EXIT_APPLY_FAILED;
            }
            if (MODE_WORKSPACE_WRITE.equals(invocation.mode())) {
                for (Path root : invocation.roots()) {
                    Path resolved = canonical(root, "workspace root", err);
                    if (resolved == null || !labelTree(resolved, err)) {
                        return EXIT_APPLY_FAILED;
                    }
                }
                Path tempParent = canonical(invocation.temp().orElseThrow(), "session temp parent", err);
                if (tempParent == null) {
                    return EXIT_APPLY_FAILED;
                }
                sessionTemp = createSessionTemp(tempParent, err);
                if (sessionTemp == null) {
                    return EXIT_APPLY_FAILED;
                }
            }
            int exit = spawnLow(token, invocation.argv(), sessionTemp, WAIT_INFINITE, err);
            return exit == SPAWN_FAILED ? EXIT_EXEC_FAILED : exit;
        } catch (ExceptionInInitializerError bindingFailure) {
            err.println("windows-acl: native bindings unavailable — " + bindingFailure.getCause());
            return EXIT_UNAVAILABLE;
        } catch (Throwable nativeFailure) {
            err.println("windows-acl: native failure: " + nativeFailure);
            return EXIT_APPLY_FAILED;
        } finally {
            deleteTree(sessionTemp);
            closeHandle(token);
        }
    }

    /**
     * 规范化为真实对象面（realpath：junction/短名/大小写）。按拼写打标会打不到目标
     * 对象（与 {@link io.javanatic.harness.sandbox.sandbox.WritableRoots} 的 realpath
     * 口径同理）；解析失败 fail-closed 点名（缺失的根在其存在前匹配不到任何东西）。
     */
    private static Path canonical(Path path, String what, PrintStream err) {
        try {
            return path.toRealPath();
        } catch (IOException missingOrUnresolvable) {
            err.println("windows-acl: " + what + " unresolvable: " + path + " (" + missingOrUnresolvable
                + ") — missing/unresolvable root is fail-closed, same as landlock root grants");
            return null;
        }
    }

    /** 会话私有临时目录（{@code --temp} 下）：创建即在 Medium 面，打 Low 标签后交给子进程。 */
    private static Path createSessionTemp(Path parent, PrintStream err) {
        try {
            Path session = Files.createTempDirectory(parent, "jh-sbx-");
            if (!labelTree(session, err)) {
                deleteTree(session);
                return null;
            }
            return session;
        } catch (IOException notCreated) {
            err.println("windows-acl: session temp dir not creatable under " + parent + ": " + notCreated);
            return null;
        }
    }

    // ---- 打标（纯 FFM） ----

    /**
     * 递归打 Low 标签（= 把该树纳入可写面）：逐对象 {@code SetNamedSecurityInfoW(LABEL)}
     * ——标签不向已存在子对象回溯（S-0 实测），故必须逐对象走全树；新对象由 (OI)(CI)
     * 继承。跳过 reparse point（junction/symlink 不追链接、不打标：链接目标另有其对象，
     * 越界方向是「拒」不是「放」）；根自身是 reparse point 则点名拒绝（打标打在链接
     * 对象上保护不到目标面，静默不标是陷阱）。任一对象失败即整树失败（fail-closed）。
     *
     * @param root 打标根（调用方已规范化）
     * @param err  诊断出口（点名对象与 errno）
     * @return true = 整棵树已打标
     */
    static boolean labelTree(Path root, PrintStream err) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Native.CAPTURE_LAYOUT);
            LabelSd label = lowLabelSd(arena, capture, err);
            if (label == null) {
                return false;
            }
            try {
                if (isReparsePoint(root, capture)) {
                    err.println("windows-acl: root is a reparse point (junction/symlink), not labeled: " + root
                        + " — 0.2 does not follow links when labeling（打标链接对象保护不到目标面）");
                    return false;
                }
                Files.walkFileTree(root, new LabelingVisitor(capture, label.sacl()));
                return true;
            } catch (IOException labelingFailed) {
                err.println("windows-acl: labeling failed: " + labelingFailed.getMessage());
                return false;
            } finally {
                free(label.base());
            }
        }
    }

    /**
     * 打标 SD：{@link #LOW_LABEL_SDDL} → 自相对 SD（control=0x8010）→ 取 SACL。
     * LABEL 位（0x10）的存在意义即免 SeSecurityPrivilege（普通用户会话可用）——
     * 探针的嵌套腿把这条落成断言。
     *
     * @return SD 基址与 SACL 绝对指针；任一步不成立（不可转换/非自相对/无 SACL）打印后 null
     */
    private static LabelSd lowLabelSd(Arena arena, MemorySegment capture, PrintStream err) {
        try {
            MemorySegment sddl = wide(arena, LOW_LABEL_SDDL);
            MemorySegment sdOut = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment sizeOut = arena.allocate(ValueLayout.JAVA_INT);
            int converted = (int) Native.CONVERT_SDDL_TO_SD.invokeExact(capture, sddl.address(), 1,
                sdOut.address(), sizeOut.address());
            long sd = sdOut.get(ValueLayout.JAVA_LONG, 0);
            if (converted == 0 || sd == 0) {
                err.println("windows-acl: label SDDL not convertible (GetLastError " + lastError(capture) + ")");
                free(sd);
                return null;
            }
            MemorySegment view = MemorySegment.ofAddress(sd).reinterpret(SD_HEADER_BYTES);
            int control = view.get(ValueLayout.JAVA_SHORT, SD_CONTROL_OFFSET) & 0xFFFF;
            if ((control & SE_SELF_RELATIVE) == 0) {
                err.println("windows-acl: label SD not self-relative (control=0x" + Integer.toHexString(control)
                    + ") — refusing");
                free(sd);
                return null;
            }
            int saclOffset = view.get(ValueLayout.JAVA_INT, SD_SACL_OFFSET_FIELD);
            if ((control & SE_SACL_PRESENT) == 0 || saclOffset == 0) {
                err.println("windows-acl: label SD carries no SACL (control=0x" + Integer.toHexString(control)
                    + ") — refusing");
                free(sd);
                return null;
            }
            return new LabelSd(sd, sd + saclOffset);
        } catch (Throwable nativeFailure) {
            err.println("windows-acl: label SD native failure: " + nativeFailure);
            return null;
        }
    }

    /** 是否 reparse point（junction/symlink）；读不到属性即 IOException（fail-closed）。 */
    private static boolean isReparsePoint(Path object, MemorySegment capture) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = wide(arena, object.toString());
            MemorySegment captureSegment = arena.allocate(Native.CAPTURE_LAYOUT);
            int attributes = (int) Native.GET_FILE_ATTRIBUTES.invokeExact(captureSegment, name.address());
            if (attributes == INVALID_FILE_ATTRIBUTES) {
                throw new IOException("GetFileAttributes failed for " + object + " (GetLastError "
                    + lastError(captureSegment) + ")");
            }
            return (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
        } catch (IOException attributesUnreadable) {
            throw attributesUnreadable;
        } catch (Throwable nativeFailure) {
            throw new IOException("attributes unreadable for " + object + ": " + nativeFailure, nativeFailure);
        }
    }

    /** 标签 SD 的内存对：LocalAlloc 基址（释放用）与其中 SACL 的绝对指针。 */
    private record LabelSd(long base, long sacl) {
    }

    /**
     * 逐对象打标访问器：一处失败即整树失败（{@link IOException} 终止 walk，调用方
     * fail-closed）。reparse point 不追不标（见 {@link #labelTree}）。
     */
    private static final class LabelingVisitor extends SimpleFileVisitor<Path> {

        private final MemorySegment capture;
        private final long sacl;

        LabelingVisitor(MemorySegment capture, long sacl) {
            this.capture = capture;
            this.sacl = sacl;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (isReparsePoint(dir, capture)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return labelObject(dir);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (isReparsePoint(file, capture)) {
                return FileVisitResult.CONTINUE;
            }
            return labelObject(file);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            throw new IOException("not labelable: " + file + " (" + failure.getMessage() + ")", failure);
        }

        /** 单对象打标；失败抛 IOException（点名对象与 errno）。 */
        private FileVisitResult labelObject(Path object) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = wide(arena, object.toString());
                int set = (int) Native.SET_NAMED_SECURITY_INFO.invokeExact(capture, name.address(), SE_FILE_OBJECT,
                    LABEL_SECURITY_INFORMATION, 0L, 0L, 0L, sacl);
                if (set != 0) {
                    throw new IOException("label not set on " + object + " (GetLastError "
                        + lastError(capture) + ")");
                }
                return FileVisitResult.CONTINUE;
            } catch (IOException labelFailed) {
                throw labelFailed;
            } catch (Throwable nativeFailure) {
                throw new IOException("labeling failed for " + object + ": " + nativeFailure, nativeFailure);
            }
        }
    }

    // ---- 令牌（低完整性 / 特权剥离） ----

    /**
     * 低完整性令牌（run 路径的形状）：当前进程令牌 → {@code CreateRestrictedToken
     * (DISABLE_MAX_PRIVILEGE, 0 受限 SID)} → {@code SetTokenInformation(TokenIntegrityLevel,
     * S-1-16-4096)}。失败返回 0（fail-closed：调用方不得生子）。
     */
    private static long lowIntegrityToken(PrintStream err) {
        return restrictedToken(DISABLE_MAX_PRIVILEGE, true, err);
    }

    /** 特权剥离令牌（探针的「标准用户」近似腿：只剥特权、不降完整性——助手的打标/建目录需 Medium 面）。 */
    private static long strippedPrivilegeToken(PrintStream err) {
        return restrictedToken(DISABLE_MAX_PRIVILEGE, false, err);
    }

    /**
     * 受限令牌基座：{@code CreateRestrictedToken(flags, 0 受限 SID)}
     * （{@code lowIntegrity} 时再置 TokenIntegrityLevel）。
     *
     * <p>S-0 订正照做：{@code SidsToRestrict.Attributes} 须为 0；低完整性 SID 以字节形
     * 直写进 {@code SID_AND_ATTRIBUTES}（attributes = SE_GROUP_INTEGRITY）。
     */
    private static long restrictedToken(int flags, boolean lowIntegrity, PrintStream err) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Native.CAPTURE_LAYOUT);
            MemorySegment tokenOut = arena.allocate(ValueLayout.JAVA_LONG);
            int opened = (int) Native.OPEN_PROCESS_TOKEN.invokeExact(capture, currentProcess(), TOKEN_ACCESS,
                tokenOut.address());
            long baseToken = tokenOut.get(ValueLayout.JAVA_LONG, 0);
            if (opened == 0) {
                err.println("windows-acl: OpenProcessToken failed (GetLastError " + lastError(capture) + ")");
                return 0;
            }
            MemorySegment restrictedOut = arena.allocate(ValueLayout.JAVA_LONG);
            int restrictedOk;
            try {
                restrictedOk = (int) Native.CREATE_RESTRICTED_TOKEN.invokeExact(capture, baseToken, flags,
                    0, 0L, 0, 0L, 0, 0L, restrictedOut.address());
            } finally {
                closeHandle(baseToken);
            }
            long restricted = restrictedOut.get(ValueLayout.JAVA_LONG, 0);
            if (restrictedOk == 0) {
                err.println("windows-acl: CreateRestrictedToken failed (GetLastError " + lastError(capture) + ")");
                return 0;
            }
            if (!lowIntegrity) {
                return restricted;
            }
            MemorySegment level = arena.allocate(SID_AND_ATTRIBUTES_SIZE);
            level.set(ValueLayout.JAVA_LONG, 0, arena.allocateFrom(ValueLayout.JAVA_BYTE, integritySid()).address());
            level.set(ValueLayout.JAVA_INT, 8, SE_GROUP_INTEGRITY);
            int levelOk = (int) Native.SET_TOKEN_INFORMATION.invokeExact(capture, restricted,
                TOKEN_INTEGRITY_LEVEL, level.address(), (int) SID_AND_ATTRIBUTES_SIZE);
            if (levelOk == 0) {
                err.println("windows-acl: SetTokenInformation(integrity low) failed (GetLastError "
                    + lastError(capture) + ")");
                closeHandle(restricted);
                return 0;
            }
            return restricted;
        } catch (ExceptionInInitializerError bindingFailure) {
            throw bindingFailure;
        } catch (Throwable nativeFailure) {
            err.println("windows-acl: token construction native failure: " + nativeFailure);
            return 0;
        }
    }

    // ---- 生子与 stdio/TEMP 面 ----

    /**
     * 以给定令牌生子并等待：目标 argv 经 {@link #commandLine} 呈命令行（lpApplicationName
     * 为 null——首词按标准路径搜索解析）；stdio 以可继承副本经 STARTF_USESTDHANDLES
     * 透传（缺哪个哪个为 NULL，缺 stdout 即输出不可见——诊断先于静默）；{@code tempDir}
     * 非空时以 UTF-16LE 环境块重定向 TEMP/TMP。等待时长由调用方定（run = 无限，平台侧
     * 击杀治理；探针 = 有界，防僵）。
     *
     * <p>子进程与助手是<b>真父子</b>（进程树可达）；助手被击杀时子进程的收敛由平台侧
     * killTree 承担（{@code descendants()} 先于本体）。
     *
     * @param token      低完整性（或探针用特权剥离）令牌
     * @param argv       目标命令
     * @param tempDir    会话临时目录（null = 不重定向，TEMP/TMP 随环境继承）
     * @param waitMillis 等待上限（{@link #WAIT_INFINITE} = 无限）
     * @param err        诊断出口
     * @return 子进程退出码；未创建/未按时退出 {@link #SPAWN_FAILED}
     */
    private static int spawnLow(long token, List<String> argv, Path tempDir, int waitMillis, PrintStream err) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Native.CAPTURE_LAYOUT);
            MemorySegment command = wide(arena, commandLine(argv));
            MemorySegment startup = arena.allocate(STARTUPINFO_W_SIZE);
            startup.set(ValueLayout.JAVA_INT, 0, (int) STARTUPINFO_W_SIZE);
            startup.set(ValueLayout.JAVA_INT, STARTUPINFO_FLAGS_OFFSET, STARTF_USESTDHANDLES);
            long stdin = duplicateInheritable(arena, capture, STD_INPUT_HANDLE);
            long stdout = duplicateInheritable(arena, capture, STD_OUTPUT_HANDLE);
            long stderr = duplicateInheritable(arena, capture, STD_ERROR_HANDLE);
            startup.set(ValueLayout.JAVA_LONG, STARTUPINFO_STDIN_OFFSET, stdin);
            startup.set(ValueLayout.JAVA_LONG, STARTUPINFO_STDOUT_OFFSET, stdout);
            startup.set(ValueLayout.JAVA_LONG, STARTUPINFO_STDERR_OFFSET, stderr);
            MemorySegment environment = tempDir == null ? MemorySegment.NULL : environmentBlock(arena, tempDir);
            MemorySegment processInfo = arena.allocate(PROCESS_INFORMATION_SIZE);
            int created;
            try {
                created = (int) Native.CREATE_PROCESS_AS_USER.invokeExact(capture, token,
                    MemorySegment.NULL.address(), command.address(), MemorySegment.NULL.address(),
                    MemorySegment.NULL.address(), 1, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT,
                    environment.address(), MemorySegment.NULL.address(), startup.address(),
                    processInfo.address());
            } finally {
                closeHandle(stdin);
                closeHandle(stdout);
                closeHandle(stderr);
            }
            if (created == 0) {
                err.println("windows-acl: CreateProcessAsUserW failed (GetLastError " + lastError(capture)
                    + ") for \"" + argv.get(0) + "\"");
                return SPAWN_FAILED;
            }
            long processHandle = processInfo.get(ValueLayout.JAVA_LONG, 0);
            long threadHandle = processInfo.get(ValueLayout.JAVA_LONG, 8);
            int pid = processInfo.get(ValueLayout.JAVA_INT, 16);
            closeHandle(threadHandle);
            try {
                int waited = (int) Native.WAIT_FOR_SINGLE_OBJECT.invokeExact(processHandle, waitMillis);
                if (waited != WAIT_OBJECT_0) {
                    err.println("windows-acl: child pid=" + pid + " did not exit (wait=" + waited
                        + ", timeout " + waitMillis + "ms)");
                    return SPAWN_FAILED;
                }
                MemorySegment exitSlot = arena.allocate(ValueLayout.JAVA_INT);
                int observed = (int) Native.GET_EXIT_CODE_PROCESS.invokeExact(capture, processHandle,
                    exitSlot.address());
                if (observed == 0) {
                    err.println("windows-acl: GetExitCodeProcess failed (GetLastError " + lastError(capture) + ")");
                    return SPAWN_FAILED;
                }
                return exitSlot.get(ValueLayout.JAVA_INT, 0);
            } finally {
                closeHandle(processHandle);
            }
        } catch (Throwable nativeFailure) {
            err.println("windows-acl: spawn native failure: " + nativeFailure);
            return SPAWN_FAILED;
        }
    }

    /**
     * std 句柄的可继承副本（STARTF_USESTDHANDLES 要求句柄可继承；Java 建助手时留的继承位
     * 是运行事实而非承诺，显式复制一份可继承的才是确定性）。句柄缺失返回 0（该 std 面在
     * 子进程里为 NULL）；原生调用失败向上抛（spawnLow 统一转 SPAWN_FAILED）。
     */
    private static long duplicateInheritable(Arena arena, MemorySegment capture, int stdHandle) throws Throwable {
        long source = (long) Native.GET_STD_HANDLE.invokeExact(stdHandle);
        if (source == 0 || source == INVALID_HANDLE_VALUE) {
            return 0;
        }
        MemorySegment duplicateOut = arena.allocate(ValueLayout.JAVA_LONG);
        int duplicated = (int) Native.DUPLICATE_HANDLE.invokeExact(capture, currentProcess(), source,
            currentProcess(), duplicateOut.address(), 0, 1, DUPLICATE_SAME_ACCESS);
        return duplicated == 0 ? 0 : duplicateOut.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * 子进程环境块（UTF-16LE、{@code name=value} 逐项 NUL 结尾、整块双 NUL 收尾；大小写
     * 不敏感排序 = Windows 环境块惯约）：继承助手环境，TEMP/TMP 覆写为会话临时目录——
     * 工具链写临时文件落 Low 面，否则被 MAC 拒（S-0 推论，嵌套腿实测）。
     */
    private static MemorySegment environmentBlock(Arena arena, Path tempDir) {
        Map<String, String> environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        environment.putAll(System.getenv());
        environment.put("TEMP", tempDir.toString());
        environment.put("TMP", tempDir.toString());
        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            block.append(entry.getKey()).append('=').append(entry.getValue()).append('\0');
        }
        block.append('\0');
        return wide(arena, block.toString());
    }

    // ---- 小工具 ----

    /** 宽字符串（UTF-16LE；尾部 NUL 由 allocate 的零填充承担）。 */
    private static MemorySegment wide(Arena arena, String text) {
        MemorySegment segment = arena.allocate(2L * (text.length() + 1));
        segment.setString(0, text, StandardCharsets.UTF_16LE);
        return segment;
    }

    private static long currentProcess() throws Throwable {
        return (long) Native.GET_CURRENT_PROCESS.invokeExact();
    }

    private static int lastError(MemorySegment capture) {
        return capture.get(ValueLayout.JAVA_INT, (int) Native.LAST_ERROR_OFFSET);
    }

    /** 关闭句柄；失败无补救路径（进程退出回收），不掩盖前序结论。 */
    private static void closeHandle(long handle) {
        if (handle == 0) {
            return;
        }
        try {
            int ignored = (int) Native.CLOSE_HANDLE.invokeExact(handle);
        } catch (Throwable closeFailure) {
            // 关闭失败无补救路径：句柄随进程退出回收
        }
    }

    /** LocalFree（返回值必须承接——语句上下文裸 invokeExact 会编成 (…)void，S-0 订正④）。 */
    private static void free(long pointer) {
        if (pointer == 0) {
            return;
        }
        try {
            long ignored = (long) Native.LOCAL_FREE.invokeExact(pointer);
        } catch (Throwable freeFailure) {
            // 释放失败无补救路径：进程退出回收，不掩盖前序结论
        }
    }

    /** 尽力删除整棵树（探针与会话临时目录收尾）；失败不掩盖前序结论（足迹已登记）。 */
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cleanupFailed) {
            // 收尾尽力：残留对象随宿主清理（探针/会话临时区）
        }
    }

    // ---- 原生调用（FFM；只在助手/Windows 宿主被触发） ----

    /**
     * FFM 句柄持有类：惰性初始化——provider 进程只走纯函数，不碰 Win32 DLL。
     *
     * <p>S-0 订正照做：{@code LocalFree} 归 kernel32（advapi32 不导出它）；Windows 捕获
     * 态段为 {@code captureStateLayout()}（12 字节，不是 POSIX 的 4）；指针/句柄形参一律
     * {@code JAVA_LONG} + 调用点 {@code .address()}（invokeExact 无隐式加宽）。
     */
    private static final class Native {

        private static final Arena LIBRARY_ARENA = Arena.global();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", LIBRARY_ARENA);
        private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", LIBRARY_ARENA);
        private static final Linker.Option CAPTURE_LAST_ERROR = Linker.Option.captureCallState("GetLastError");
        private static final MemoryLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
        private static final long LAST_ERROR_OFFSET = CAPTURE_LAYOUT.byteOffset(
            MemoryLayout.PathElement.groupElement("GetLastError"));

        // kernel32
        private static final MethodHandle GET_CURRENT_PROCESS = bind(KERNEL32, "GetCurrentProcess",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        private static final MethodHandle CLOSE_HANDLE = bind(KERNEL32, "CloseHandle",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        private static final MethodHandle LOCAL_FREE = bind(KERNEL32, "LocalFree",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        private static final MethodHandle WAIT_FOR_SINGLE_OBJECT = bind(KERNEL32, "WaitForSingleObject",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        private static final MethodHandle GET_EXIT_CODE_PROCESS = bind(KERNEL32, "GetExitCodeProcess",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
            CAPTURE_LAST_ERROR);
        private static final MethodHandle GET_FILE_ATTRIBUTES = bind(KERNEL32, "GetFileAttributesW",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG), CAPTURE_LAST_ERROR);
        private static final MethodHandle GET_STD_HANDLE = bind(KERNEL32, "GetStdHandle",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        private static final MethodHandle DUPLICATE_HANDLE = bind(KERNEL32, "DuplicateHandle",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT), CAPTURE_LAST_ERROR);

        // advapi32
        private static final MethodHandle OPEN_PROCESS_TOKEN = bind(ADVAPI32, "OpenProcessToken",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG), CAPTURE_LAST_ERROR);
        private static final MethodHandle CREATE_RESTRICTED_TOKEN = bind(ADVAPI32, "CreateRestrictedToken",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), CAPTURE_LAST_ERROR);
        private static final MethodHandle SET_TOKEN_INFORMATION = bind(ADVAPI32, "SetTokenInformation",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT), CAPTURE_LAST_ERROR);
        private static final MethodHandle CONVERT_SDDL_TO_SD = bind(ADVAPI32,
            "ConvertStringSecurityDescriptorToSecurityDescriptorW",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), CAPTURE_LAST_ERROR);
        private static final MethodHandle SET_NAMED_SECURITY_INFO = bind(ADVAPI32, "SetNamedSecurityInfoW",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG), CAPTURE_LAST_ERROR);
        private static final MethodHandle CREATE_PROCESS_AS_USER = bind(ADVAPI32, "CreateProcessAsUserW",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG), CAPTURE_LAST_ERROR);

        private Native() {
        }

        private static MethodHandle bind(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor,
                                         Linker.Option... options) {
            return Linker.nativeLinker().downcallHandle(lookup.find(symbol).orElseThrow(
                () -> new IllegalStateException("missing symbol " + symbol)), descriptor, options);
        }
    }
}
