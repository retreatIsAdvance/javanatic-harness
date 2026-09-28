/*
 * it25 首步实探 S-0 —— 真 Windows 环境运行（UTM VM / CI windows runner）；不参与构建（it24 探针先例）。
 *
 * 覆盖（裁决增强 4）；任一事实不成立都会改变 windows-acl 的实现形状，故先取证再动工：
 *   1. 系统/编码事实：os/arch/JDK、native/stdout/stderr/file.encoding、PROCESSOR_ARCHITECTURE、中文样本
 *   2. pwsh 存在性与版本（Windows shell 候选链的事实前提；Win11 自带 powershell 5.1，pwsh 未必在）
 *   3. sun.misc.Signal("INT") handle + raise（取消验收地基；Windows 无 POSIX SIGINT）
 *   4. FFM 全谱：kernel32/advapi32 符号在场 → OpenProcessToken → AllocateAndInitializeSid →
 *      CreateRestrictedToken(WRITE_RESTRICTED + restricting SID) → GetNamedSecurityInfoW /
 *      SetEntriesInAclW / SetNamedSecurityInfoW（per-workspace SID 授权）→ CreateProcessAsUserW 生子 →
 *      三条写腿（普通对照 / 未授权拒写 / 授权可写）→ CreateProcessWithTokenW 备选路径
 *
 * 运行（Windows + JDK 25，本文件所在目录，stdout 全量回贴落盘）：
 *   java --enable-native-access=ALL-UNNAMED S-0-windows-probe.java
 *
 * 读法（每行 [ffm] … ok=… errno=…；腿级违规打 [FAIL]；结尾 failures=N）：
 *   [ctrl] 普通写必须 exists=true —— 不成立则 DAC/路径前提坏了，后续腿结论作废
 *   deny-write 必须 exists=false —— 写受限令牌真生效
 *   grant-write 必须 exists=true —— per-workspace SID 授权机制真可用（不成立=工作区写被全拒，实现要改）
 *   with-token errno=1314 (ERROR_PRIVILEGE_NOT_HELD) 属预期（备选路径，主路径不依赖它）
 */
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class Probe {

    // ---- Win32 常量（原型 = Windows SDK 头文件） ----
    static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    static final int TOKEN_DUPLICATE = 0x0002;
    static final int TOKEN_QUERY = 0x0008;
    static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    static final int TOKEN_ADJUST_SESSIONID = 0x0100;
    static final int DISABLE_MAX_PRIVILEGE = 0x0001;
    static final int WRITE_RESTRICTED = 0x0008;
    static final int SE_GROUP_ENABLED = 0x00000004;
    static final int CREATE_NO_WINDOW = 0x08000000;
    static final int SE_FILE_OBJECT = 1;
    static final int DACL_SECURITY_INFORMATION = 0x00000004;
    static final int GRANT_ACCESS = 1;
    static final int INHERIT_OBJECT_AND_CONTAINER = 0x3;   // OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE
    static final int FILE_ALL_ACCESS = 0x001F01FF;
    static final long ERROR_PRIVILEGE_NOT_HELD = 1314L;

    // ---- x64 结构布局（布局不符 = 实现形状要改，实探要的正是这条） ----
    static final long STARTUPINFO_W_SIZE = 104;
    static final long PROCESS_INFORMATION_SIZE = 24;
    static final long SID_AND_ATTRIBUTES_SIZE = 16;        // 指针 8 + DWORD 4 + pad 4
    static final long EXPLICIT_ACCESS_W_SIZE = 48;         // 16 头 + TRUSTEE_W 32
    static final long TRUSTEE_PTSTR_NAME_OFFSET = 40;      // Trustee 起于 16，ptstrName 再 +24

    static final int WAIT_TIMEOUT_MS = 20000;
    static final int STILL_ACTIVE = 259;

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    static final Linker LINKER = Linker.nativeLinker();
    static final Arena ARENA = Arena.ofShared();
    // "GetLastError" 只在 Windows 链接器注册；非 Windows 造这个选项当场抛（smoke 腿要能空跑）
    static final Linker.Option ERR = WINDOWS ? Linker.Option.captureCallState("GetLastError") : null;

    static MethodHandle W32_WAIT_ONE;
    static MethodHandle W32_GET_EXIT_CODE;
    static MethodHandle W32_CLOSE_HANDLE;

    static int failures;

    public static void main(String[] args) throws Throwable {
        System.out.println("== it25 S-0 windows probe ==");
        facts();
        pwsh();
        signal();
        if (!WINDOWS) {
            System.out.println("[SKIP] FFM win32 legs: not Windows");
        } else {
            ffm();
        }
        System.out.println("== probe done, failures=" + failures + " ==");
    }

    static void facts() {
        System.out.println("[os] name=" + System.getProperty("os.name")
            + " arch=" + System.getProperty("os.arch")
            + " version=" + System.getProperty("os.version")
            + " env.PROCESSOR_ARCHITECTURE=" + System.getenv("PROCESSOR_ARCHITECTURE"));
        System.out.println("[jdk] version=" + System.getProperty("java.version")
            + " home=" + System.getProperty("java.home"));
        for (String key : List.of("native.encoding", "stdout.encoding", "stderr.encoding", "file.encoding")) {
            System.out.println("[enc] " + key + "=" + System.getProperty(key));
        }
        System.out.println("[enc] sample: 中文 — em-dash");
        System.out.println("[paths] user.home=" + System.getProperty("user.home")
            + " tmpdir=" + System.getProperty("java.io.tmpdir"));
    }

    static void pwsh() {
        for (String exe : List.of("pwsh", "powershell")) {
            try {
                Process p = new ProcessBuilder(exe, "-NoProfile", "-NonInteractive", "-Command",
                    "$PSVersionTable.PSVersion.ToString()").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                boolean done = p.waitFor(30, TimeUnit.SECONDS);
                System.out.println("[pwsh] " + exe + " exit=" + (done ? p.exitValue() : "timeout") + " version=" + out);
            } catch (Exception e) {
                System.out.println("[pwsh] " + exe + " unavailable: " + e.getMessage());
            }
        }
    }

    static void signal() {
        AtomicBoolean fired = new AtomicBoolean();
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), s -> fired.set(true));
            sun.misc.Signal.raise(new sun.misc.Signal("INT"));
            Thread.sleep(200);
            System.out.println("[signal] Signal.raise(INT) -> handler fired=" + fired.get());
        } catch (Throwable t) {
            System.out.println("[signal] INT handle/raise unavailable or failed: " + t);
        }
    }

    // ---- FFM 全谱 ----

    static void ffm() throws Throwable {
        SymbolLookup k32 = lookup("kernel32.dll");
        SymbolLookup adv = lookup("advapi32.dll");
        present("kernel32", k32, "GetCurrentProcess", "WaitForSingleObject", "GetExitCodeProcess", "CloseHandle");
        present("advapi32", adv, "OpenProcessToken", "CreateRestrictedToken", "AllocateAndInitializeSid",
            "FreeSid", "CreateProcessAsUserW", "CreateProcessWithTokenW", "ConvertSidToStringSidW",
            "GetNamedSecurityInfoW", "SetEntriesInAclW", "SetNamedSecurityInfoW", "LocalFree");
        if (k32 == null || adv == null) {
            return;
        }

        MemorySegment err = ARENA.allocate(ValueLayout.JAVA_INT);

        MethodHandle getCurrentProcess = LINKER.downcallHandle(sym(k32, "GetCurrentProcess"),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        W32_WAIT_ONE = LINKER.downcallHandle(sym(k32, "WaitForSingleObject"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        W32_GET_EXIT_CODE = LINKER.downcallHandle(sym(k32, "GetExitCodeProcess"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        W32_CLOSE_HANDLE = LINKER.downcallHandle(sym(k32, "CloseHandle"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        MethodHandle openProcessToken = LINKER.downcallHandle(sym(adv, "OpenProcessToken"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG), ERR);
        MethodHandle allocSid = LINKER.downcallHandle(sym(adv, "AllocateAndInitializeSid"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_BYTE,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG), ERR);
        MethodHandle createRestricted = LINKER.downcallHandle(sym(adv, "CreateRestrictedToken"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        MethodHandle createProcessAsUser = LINKER.downcallHandle(sym(adv, "CreateProcessAsUserW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG), ERR);
        MethodHandle createProcessWithToken = LINKER.downcallHandle(sym(adv, "CreateProcessWithTokenW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        MethodHandle getNamedSecurity = LINKER.downcallHandle(sym(adv, "GetNamedSecurityInfoW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        MethodHandle setEntriesInAcl = LINKER.downcallHandle(sym(adv, "SetEntriesInAclW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        MethodHandle setNamedSecurity = LINKER.downcallHandle(sym(adv, "SetNamedSecurityInfoW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG), ERR);
        MethodHandle localFree = LINKER.downcallHandle(sym(adv, "LocalFree"),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        MethodHandle freeSid = LINKER.downcallHandle(sym(adv, "FreeSid"),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        MethodHandle sidToString = LINKER.downcallHandle(sym(adv, "ConvertSidToStringSidW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);

        // 1) 当前进程令牌
        long process = (long) getCurrentProcess.invokeExact();
        MemorySegment tokenSlot = ARENA.allocate(ValueLayout.JAVA_LONG);
        int access = TOKEN_ASSIGN_PRIMARY | TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT | TOKEN_ADJUST_SESSIONID;
        int openOk = (int) openProcessToken.invokeExact(err, process, access, tokenSlot.address());
        long token = tokenSlot.get(ValueLayout.JAVA_LONG, 0);
        report("OpenProcessToken", openOk != 0, err, "token=0x" + Long.toHexString(token));
        if (openOk == 0) {
            return;
        }

        // 2) 随机 per-session SID（SECURITY_NT_AUTHORITY + 1 个 sub-authority）
        MemorySegment authority = ARENA.allocate(6);
        authority.set(ValueLayout.JAVA_BYTE, 5, (byte) 5);
        int sub0 = (int) (System.nanoTime() & 0x7FFFFFFFL);
        MemorySegment sidSlot = ARENA.allocate(ValueLayout.JAVA_LONG);
        int sidOk = (int) allocSid.invokeExact(err, authority.address(), (byte) 1,
            sub0, 0, 0, 0, 0, 0, 0, sidSlot.address());
        long sid = sidSlot.get(ValueLayout.JAVA_LONG, 0);
        report("AllocateAndInitializeSid", sidOk != 0, err, "sid=0x" + Long.toHexString(sid) + " sub0=" + sub0);
        if (sid != 0) {
            System.out.println("[ffm] sid string = " + sidString(sidToString, err, localFree, sid));
        }

        // 3) 受限令牌（WRITE_RESTRICTED + DISABLE_MAX_PRIVILEGE + 1 个 restricting SID）
        if (sid != 0) {
            MemorySegment restrictArray = ARENA.allocate(SID_AND_ATTRIBUTES_SIZE);
            restrictArray.set(ValueLayout.JAVA_LONG, 0, sid);
            restrictArray.set(ValueLayout.JAVA_INT, 8, SE_GROUP_ENABLED);
            MemorySegment newTokenSlot = ARENA.allocate(ValueLayout.JAVA_LONG);
            int rtOk = (int) createRestricted.invokeExact(err, token, DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED,
                0, 0L, 0, 0L, 1, restrictArray.address(), newTokenSlot.address());
            long restrictedToken = newTokenSlot.get(ValueLayout.JAVA_LONG, 0);
            report("CreateRestrictedToken", rtOk != 0, err, "restrictedToken=0x" + Long.toHexString(restrictedToken));

            if (rtOk != 0) {
                legs(createProcessAsUser, createProcessWithToken, restrictedToken, sid, err,
                    getNamedSecurity, setEntriesInAcl, setNamedSecurity, localFree);
            } else {
                System.out.println("[SKIP] child legs: no restricted token");
            }
        } else {
            System.out.println("[SKIP] child legs: no SID");
        }

        if (sid != 0) {
            long freed = (long) freeSid.invokeExact(sid);
            System.out.println("[ffm] FreeSid -> " + (freed == 0 ? "ok" : "0x" + Long.toHexString(freed)));
        }
    }

    /** 三条写腿 + 备选路径；写腿语义见文件头。 */
    static void legs(MethodHandle createProcessAsUser, MethodHandle createProcessWithToken, long restrictedToken,
            long sid, MemorySegment err, MethodHandle getNamedSecurity, MethodHandle setEntriesInAcl,
            MethodHandle setNamedSecurity, MethodHandle localFree) throws Throwable {
        Path root = Files.createTempDirectory("jh-probe-");
        Path denyDir = Files.createDirectory(root.resolve("deny"));
        Path grantDir = Files.createDirectory(root.resolve("grant"));
        System.out.println("[legs] root=" + root);

        // 正对照：普通进程写（防 DAC 误绿）
        Path plainTarget = root.resolve("plain.txt");
        System.out.println("[ctrl] plain write exit=" + plainWrite(plainTarget)
            + " exists=" + Files.exists(plainTarget) + "  (必须 exists=true)");
        if (!Files.exists(plainTarget)) {
            failures++;
            System.out.println("[FAIL] plain control write failed — DAC/路径前提不成立，后续腿结论不可用");
        }

        // 未授权拒写（restricted SID 不在 denyDir 的 DACL 上）
        spawn(createProcessAsUser, err, restrictedToken,
            "cmd.exe /d /c echo x > \"" + denyDir.resolve("denied.txt") + "\"", root, "deny-write");
        if (Files.exists(denyDir.resolve("denied.txt"))) {
            failures++;
            System.out.println("[FAIL] restricted write NOT denied — 写受限令牌未生效");
        } else {
            System.out.println("[ffm] deny-write: exists=false as expected");
        }

        // per-workspace SID 授权后应可写（授权机制可用性 = 实现形状前提）
        if (grant(grantDir, sid, err, getNamedSecurity, setEntriesInAcl, setNamedSecurity, localFree)) {
            Path grantedTarget = grantDir.resolve("granted.txt");
            spawn(createProcessAsUser, err, restrictedToken,
                "cmd.exe /d /c echo x > \"" + grantedTarget + "\"", root, "grant-write");
            if (Files.exists(grantedTarget)) {
                System.out.println("[ffm] grant-write: exists=true as expected");
            } else {
                failures++;
                System.out.println("[FAIL] grant-write denied — per-workspace SID 授权机制不可用（实现形状要改）");
            }
        }

        // 备选路径：CreateProcessWithTokenW（需 SeImpersonatePrivilege；1314 属预期）
        spawn(createProcessWithToken, err, restrictedToken, "cmd.exe /d /c exit 0", root, "with-token");
    }

    /** per-workspace SID 授权：读现有 DACL → 合并可继承授权项 → 写回。 */
    static boolean grant(Path dir, long sid, MemorySegment err, MethodHandle getNamedSecurity,
            MethodHandle setEntriesInAcl, MethodHandle setNamedSecurity, MethodHandle localFree) throws Throwable {
        MemorySegment name = wide(dir.toString());
        MemorySegment ownerOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment groupOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment daclOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment saclOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment sdOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        int readOk = (int) getNamedSecurity.invokeExact(err, name.address(), SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION, ownerOut.address(), groupOut.address(), daclOut.address(),
            saclOut.address(), sdOut.address());
        long sd = sdOut.get(ValueLayout.JAVA_LONG, 0);
        long oldDacl = daclOut.get(ValueLayout.JAVA_LONG, 0);
        report("GetNamedSecurityInfoW(" + dir.getFileName() + ")", readOk == 0, err,
            "oldDacl=0x" + Long.toHexString(oldDacl));
        if (readOk != 0) {
            if (sd != 0) {
                localFree.invokeExact(sd);
            }
            return false;
        }

        MemorySegment ea = ARENA.allocate(EXPLICIT_ACCESS_W_SIZE);
        ea.set(ValueLayout.JAVA_INT, 0, FILE_ALL_ACCESS);
        ea.set(ValueLayout.JAVA_INT, 4, GRANT_ACCESS);
        ea.set(ValueLayout.JAVA_INT, 8, INHERIT_OBJECT_AND_CONTAINER);
        ea.set(ValueLayout.JAVA_LONG, 16, 0L);                     // pMultipleTrustee
        ea.set(ValueLayout.JAVA_INT, 24, 0);                       // MultipleTrusteeOperation
        ea.set(ValueLayout.JAVA_INT, 28, 0);                       // TrusteeForm = TRUSTEE_IS_SID
        ea.set(ValueLayout.JAVA_INT, 32, 0);                       // TrusteeType = TRUSTEE_IS_UNKNOWN
        ea.set(ValueLayout.JAVA_LONG, TRUSTEE_PTSTR_NAME_OFFSET, sid);

        MemorySegment newAclOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        int mergeOk = (int) setEntriesInAcl.invokeExact(err, 1, ea.address(), oldDacl, newAclOut.address());
        long newAcl = newAclOut.get(ValueLayout.JAVA_LONG, 0);
        report("SetEntriesInAclW", mergeOk == 0, err,
            "ret=" + mergeOk + " newAcl=0x" + Long.toHexString(newAcl));
        if (mergeOk != 0) {
            localFree.invokeExact(sd);
            return false;
        }

        int writeOk = (int) setNamedSecurity.invokeExact(err, name.address(), SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION, 0L, 0L, newAcl, 0L);
        report("SetNamedSecurityInfoW(" + dir.getFileName() + ")", writeOk == 0, err, "ret=" + writeOk);
        localFree.invokeExact(newAcl);
        localFree.invokeExact(sd);
        return writeOk == 0;
    }

    /** 正对照：普通进程写（防 DAC 误绿）。 */
    static int plainWrite(Path target) {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/d", "/c", "echo ok > \"" + target + "\"")
                .redirectErrorStream(true).start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            return done ? p.exitValue() : -1;
        } catch (Exception e) {
            System.out.println("[ctrl] plain write failed: " + e.getMessage());
            return -1;
        }
    }

    /** 用给定令牌建子进程并等退出码；建进程失败打印 GetLastError。 */
    static boolean spawn(MethodHandle fn, MemorySegment err, long token, String cmdline, Path cwd, String label)
            throws Throwable {
        MemorySegment cmd = wide(cmdline);
        MemorySegment si = ARENA.allocate(STARTUPINFO_W_SIZE);
        si.set(ValueLayout.JAVA_INT, 0, (int) STARTUPINFO_W_SIZE);
        MemorySegment pi = ARENA.allocate(PROCESS_INFORMATION_SIZE);
        int ok;
        if (fn.type().parameterCount() == 12) {   // CreateProcessAsUserW + call state
            ok = (int) fn.invokeExact(err, token, 0L, cmd.address(), 0L, 0L, 0, CREATE_NO_WINDOW, 0L, 0L,
                si.address(), pi.address());
        } else {                                   // CreateProcessWithTokenW + call state
            ok = (int) fn.invokeExact(err, token, 0, 0L, cmd.address(), CREATE_NO_WINDOW, 0L, 0L,
                si.address(), pi.address());
        }
        if (ok == 0) {
            int lastError = err.get(ValueLayout.JAVA_INT, 0);
            System.out.println("[ffm] " + label + ": spawn FAILED errno=" + lastError
                + (lastError == ERROR_PRIVILEGE_NOT_HELD ? " (ERROR_PRIVILEGE_NOT_HELD)" : ""));
            return false;
        }
        long hProcess = pi.get(ValueLayout.JAVA_LONG, 0);
        long hThread = pi.get(ValueLayout.JAVA_LONG, 8);
        int pid = pi.get(ValueLayout.JAVA_INT, 16);
        int exitCode = waitExit(hProcess, err);
        close(hThread);
        close(hProcess);
        System.out.println("[ffm] " + label + ": pid=" + pid + " exit=" + exitCode
            + (exitCode == STILL_ACTIVE ? " (still active!)" : ""));
        return true;
    }

    static int waitExit(long hProcess, MemorySegment err) throws Throwable {
        int waited = (int) W32_WAIT_ONE.invokeExact(hProcess, WAIT_TIMEOUT_MS);
        MemorySegment code = ARENA.allocate(ValueLayout.JAVA_INT);
        int ok = (int) W32_GET_EXIT_CODE.invokeExact(err, hProcess, code.address());
        return ok != 0 && waited == 0 ? code.get(ValueLayout.JAVA_INT, 0) : -1;
    }

    static void close(long handle) throws Throwable {
        if (handle != 0) {
            W32_CLOSE_HANDLE.invokeExact(handle);
        }
    }

    static String sidString(MethodHandle fn, MemorySegment err, MethodHandle localFree, long sid) throws Throwable {
        MemorySegment out = ARENA.allocate(ValueLayout.JAVA_LONG);
        int ok = (int) fn.invokeExact(err, sid, out.address());
        if (ok == 0) {
            failures++;
            return "(ConvertSidToStringSidW failed errno=" + err.get(ValueLayout.JAVA_INT, 0) + ")";
        }
        long str = out.get(ValueLayout.JAVA_LONG, 0);
        String text = MemorySegment.ofAddress(str).reinterpret(256).getString(0, StandardCharsets.UTF_16LE);
        localFree.invokeExact(str);
        return text;
    }

    static MemorySegment wide(String s) {
        MemorySegment seg = ARENA.allocate((s.length() + 1) * 2L);
        seg.setString(0, s, StandardCharsets.UTF_16LE);
        return seg;
    }

    static MemorySegment sym(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name));
    }

    static SymbolLookup lookup(String dll) {
        try {
            return SymbolLookup.libraryLookup(dll, ARENA);
        } catch (Throwable t) {
            failures++;
            System.out.println("[ffm][FAIL] libraryLookup(" + dll + "): " + t);
            return null;
        }
    }

    static void present(String dll, SymbolLookup lookup, String... names) {
        if (lookup == null) {
            return;
        }
        for (String name : names) {
            boolean found = lookup.find(name).isPresent();
            if (!found) {
                failures++;
            }
            System.out.println("[ffm] " + dll + "!" + name + " = " + (found ? "present" : "MISSING"));
        }
    }

    static void report(String call, boolean ok, MemorySegment err, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.println("[ffm] " + call + " ok=" + ok + " errno=" + err.get(ValueLayout.JAVA_INT, 0)
            + " " + detail);
    }
}
