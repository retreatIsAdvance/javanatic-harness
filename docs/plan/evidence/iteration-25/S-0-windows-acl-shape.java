/*
 * it25 S-0 形状追查（一次性诊断，非探针套件；UTM Win11 ARM64 VM 内 C:\jh\mx 真跑，轮次 7–12）。
 *
 * 背景：S-0 探针首跑（S-0-windows-probe.txt，failures=1）证明 05 §6 既定设计
 *       （WRITE_RESTRICTED 受限令牌 + per-workspace SID 授权）在 CREATE_NO_WINDOW 下
 *       子进程 0xC0000142 STATUS_DLL_INIT_FAILED——令牌构造与 ACL 授权都成功，死在启动初始化。
 * 追查结论（原始输出逐轮见 S-0-windows-acl-shape.txt）：
 *   1) 受限 SID 列表含 Administrators 即可启动，但写限制形同虚设（默认 DACL 普遍授予
 *      Administrators ⇒ 未授权目录也能写）——WRITE_RESTRICTED 形状不可用；
 *   2) 低完整性（Low IL）形状可用：CreateRestrictedToken(DISABLE_MAX_PRIVILEGE, 0 SID)
 *      + SetTokenInformation(TokenIntegrityLevel, {S-1-16-4096, SE_GROUP_INTEGRITY})
 *      ⇒ 子进程在 CREATE_NO_WINDOW 下正常启动；写 Low 标签目录成功、写中标签目录被拒；
 *   3) 打标可纯 FFM：ConvertStringSecurityDescriptorToSecurityDescriptorW("S:(ML;OICI;NW;;;LW)")
 *      + SetNamedSecurityInfoW(LABEL_SECURITY_INFORMATION=0x10)（0x4 SACL 位在管理员下亦成功）；
 *   4) 标签不向已存在子对象回溯传播（pre.txt 打标前已存在 ⇒ 子进程写被拒）⇒ 会话可写区域
 *      需递归打标。
 *
 * 本文件 = 追查所用诊断的**最终修订**（对应轮次 12：结论 2/3/4 的复现腿；轮次 7–11 用中间修订，
 * 原始输出见证据 .txt）。运行（Windows + JDK 25，工作目录 C:\jh\mx）：
 *   javac -encoding UTF-8 -d . RestrictMatrix.java
 *   java --enable-native-access=ALL-UNNAMED -cp . RestrictMatrix
 */
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class RestrictMatrix {

    static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    static final int TOKEN_DUPLICATE = 0x0002;
    static final int TOKEN_QUERY = 0x0008;
    static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    static final int TOKEN_ADJUST_SESSIONID = 0x0100;
    static final int TOKEN_INTEGRITY_LEVEL_CLASS = 25;
    static final int SE_GROUP_INTEGRITY = 0x00000020;
    static final int DISABLE_MAX_PRIVILEGE = 0x0001;
    static final int CREATE_NO_WINDOW = 0x08000000;
    static final int SE_FILE_OBJECT = 1;
    static final int SACL_SECURITY_INFORMATION = 0x00000004;
    static final int LABEL_SECURITY_INFORMATION = 0x00000010;
    static final int WAIT_TIMEOUT_MS = 20000;
    static final int SE_SACL_PRESENT = 0x00000010;
    static final int SE_SELF_RELATIVE = 0x00008000;

    static final long SID_AND_ATTRIBUTES_SIZE = 16;
    static final long STARTUPINFO_W_SIZE = 104;
    static final long PROCESS_INFORMATION_SIZE = 24;

    static final Linker LINKER = Linker.nativeLinker();
    static final Arena ARENA = Arena.ofShared();
    static final Linker.Option ERR = Linker.Option.captureCallState("GetLastError");
    static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();

    static MethodHandle openProcessToken;
    static MethodHandle getTokenInformation;
    static MethodHandle setTokenInformation;
    static MethodHandle allocSid;
    static MethodHandle createRestricted;
    static MethodHandle createProcessAsUser;
    static MethodHandle waitOne;
    static MethodHandle getExitCode;
    static MethodHandle closeHandle;
    static MethodHandle localFree;
    static MethodHandle convertSidToString;
    static MethodHandle getSecurityInfoName;
    static MethodHandle setSecurityInfoName;
    static MethodHandle convertSddlToSd;

    interface Step {
        void run() throws Throwable;
    }

    public static void main(String[] args) throws Throwable {
        SymbolLookup k32 = lookup("kernel32.dll");
        SymbolLookup adv = lookup("advapi32.dll");

        MethodHandle getCurrentProcess = LINKER.downcallHandle(sym(k32, "GetCurrentProcess"),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        openProcessToken = LINKER.downcallHandle(sym(adv, "OpenProcessToken"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG), ERR);
        getTokenInformation = LINKER.downcallHandle(sym(adv, "GetTokenInformation"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG), ERR);
        setTokenInformation = LINKER.downcallHandle(sym(adv, "SetTokenInformation"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT), ERR);
        allocSid = LINKER.downcallHandle(sym(adv, "AllocateAndInitializeSid"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_BYTE,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG), ERR);
        createRestricted = LINKER.downcallHandle(sym(adv, "CreateRestrictedToken"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        createProcessAsUser = LINKER.downcallHandle(sym(adv, "CreateProcessAsUserW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG), ERR);
        waitOne = LINKER.downcallHandle(sym(k32, "WaitForSingleObject"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        getExitCode = LINKER.downcallHandle(sym(k32, "GetExitCodeProcess"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        closeHandle = LINKER.downcallHandle(sym(k32, "CloseHandle"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        localFree = LINKER.downcallHandle(sym(k32, "LocalFree"),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        convertSidToString = LINKER.downcallHandle(sym(adv, "ConvertSidToStringSidW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        getSecurityInfoName = LINKER.downcallHandle(sym(adv, "GetNamedSecurityInfoW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);
        setSecurityInfoName = LINKER.downcallHandle(sym(adv, "SetNamedSecurityInfoW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG), ERR);
        convertSddlToSd = LINKER.downcallHandle(sym(adv, "ConvertStringSecurityDescriptorToSecurityDescriptorW"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), ERR);

        MemorySegment err = ARENA.allocate(CAPTURE);
        long process = (long) getCurrentProcess.invokeExact();
        MemorySegment tokenSlot = ARENA.allocate(ValueLayout.JAVA_LONG);
        int openOk = (int) openProcessToken.invokeExact(err, process,
            TOKEN_ASSIGN_PRIMARY | TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT | TOKEN_ADJUST_SESSIONID,
            tokenSlot.address());
        long token = tokenSlot.get(ValueLayout.JAVA_LONG, 0);
        System.out.println("[mx] OpenProcessToken ok=" + (openOk != 0));
        if (openOk == 0) {
            return;
        }

        long lowIlSid = makeSid(err, new byte[] { 0, 0, 0, 0, 0, 16 }, 4096);
        System.out.println("[mx] lowIL=" + sidToString(err, lowIlSid));

        Path lowDir = Path.of("C:\\jh\\mx\\ffm-low");
        Path plainDir = Path.of("C:\\jh\\mx\\ffm-plain");
        Files.createDirectories(lowDir);
        Files.createDirectories(plainDir);
        Path preFile = lowDir.resolve("pre.txt");
        Files.writeString(preFile, "pre-existing\n");
        System.out.println("[mx] lowDir=" + lowDir + " preFile exists=" + Files.exists(preFile));

        Path labeled = lowDir.resolve("new.txt");
        Path plainTarget = plainDir.resolve("new.txt");

        long tokenL = makeToken(err, token, DISABLE_MAX_PRIVILEGE, "L base");
        setIntegrityLow(err, tokenL, lowIlSid);

        attempt("labelSddl", () -> labelSddl(err, lowDir));
        attempt("Lwrite", () -> spawn("L write lowDir/new.txt", err, tokenL,
            "cmd.exe /d /c echo x > \"" + labeled + "\"", labeled));
        attempt("LwritePre", () -> {
            spawn("L write lowDir/pre.txt (pre-existing before label)", err, tokenL,
                "cmd.exe /d /c echo x > \"" + preFile + "\"", preFile);
            System.out.println("[mx] pre.txt after: size=" + Files.size(preFile) + " (13=unchanged/denied, 4=overwritten)");
        });
        attempt("LwritePlain", () -> spawn("L write plainDir/new.txt", err, tokenL,
            "cmd.exe /d /c echo x > \"" + plainTarget + "\"", plainTarget));

        System.out.println("[mx] done");
    }

    static void attempt(String label, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            System.out.println("[mx] EXC " + label + ": " + t);
        }
    }

    /** 纯 FFM 打 Low 标签：SDDL → SD → 取 SACL → 先试 LABEL(0x10)，再试 SACL(0x4)。 */
    static void labelSddl(MemorySegment err, Path dir) throws Throwable {
        MemorySegment sddl = wide("S:(ML;OICI;NW;;;LW)");
        MemorySegment sdOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment sizeOut = ARENA.allocate(ValueLayout.JAVA_INT);
        int ok = (int) convertSddlToSd.invokeExact(err, sddl.address(), 1, sdOut.address(), sizeOut.address());
        long sd = sdOut.get(ValueLayout.JAVA_LONG, 0);
        System.out.println("[mx] ConvertStringSecurityDescriptorToSecurityDescriptorW ok=" + (ok != 0)
            + " errno=" + err.get(ValueLayout.JAVA_INT, 0) + " sd=0x" + Long.toHexString(sd)
            + " size=" + sizeOut.get(ValueLayout.JAVA_INT, 0));
        if (ok == 0) {
            return;
        }
        MemorySegment view = MemorySegment.ofAddress(sd).reinterpret(256);
        int control = view.get(ValueLayout.JAVA_SHORT, 2) & 0xFFFF;
        boolean selfRelative = (control & SE_SELF_RELATIVE) != 0;
        long sacl = 0;
        if (selfRelative) {
            int off = view.get(ValueLayout.JAVA_INT, 12);
            sacl = off == 0 ? 0 : sd + off;
        } else {
            sacl = view.get(ValueLayout.JAVA_LONG, 24);
        }
        System.out.println("[mx] SD control=0x" + Integer.toHexString(control) + " selfRelative=" + selfRelative
            + " saclPresent=" + ((control & SE_SACL_PRESENT) != 0) + " sacl=0x" + Long.toHexString(sacl));

        MemorySegment name = wide(dir.toString());
        int labelOk = (int) setSecurityInfoName.invokeExact(err, name.address(), SE_FILE_OBJECT,
            LABEL_SECURITY_INFORMATION, 0L, 0L, 0L, sacl);
        System.out.println("[mx] SetNamedSecurityInfoW(LABEL=0x10) ok=" + (labelOk == 0) + " errno="
            + err.get(ValueLayout.JAVA_INT, 0));

        int saclOk = (int) setSecurityInfoName.invokeExact(err, name.address(), SE_FILE_OBJECT,
            SACL_SECURITY_INFORMATION, 0L, 0L, 0L, sacl);
        System.out.println("[mx] SetNamedSecurityInfoW(SACL=0x4) ok=" + (saclOk == 0) + " errno="
            + err.get(ValueLayout.JAVA_INT, 0));

        MemorySegment ownerOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment groupOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment daclOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment saclOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        MemorySegment sdReadOut = ARENA.allocate(ValueLayout.JAVA_LONG);
        int readOk = (int) getSecurityInfoName.invokeExact(err, name.address(), SE_FILE_OBJECT,
            LABEL_SECURITY_INFORMATION, ownerOut.address(), groupOut.address(), daclOut.address(),
            saclOut.address(), sdReadOut.address());
        System.out.println("[mx] readback GetNamedSecurityInfoW(LABEL) ok=" + (readOk == 0) + " errno="
            + err.get(ValueLayout.JAVA_INT, 0) + " sacl=0x" + Long.toHexString(saclOut.get(ValueLayout.JAVA_LONG, 0)));
        freeLocal(sdReadOut.get(ValueLayout.JAVA_LONG, 0));
        freeLocal(sd);
    }

    static void setIntegrityLow(MemorySegment err, long token, long lowIlSid) throws Throwable {
        MemorySegment ml = ARENA.allocate(SID_AND_ATTRIBUTES_SIZE);
        ml.set(ValueLayout.JAVA_LONG, 0, lowIlSid);
        ml.set(ValueLayout.JAVA_INT, 8, SE_GROUP_INTEGRITY);
        int ok = (int) setTokenInformation.invokeExact(err, token, TOKEN_INTEGRITY_LEVEL_CLASS,
            ml.address(), (int) SID_AND_ATTRIBUTES_SIZE);
        System.out.println("[mx] SetTokenInformation(IL=Low) ok=" + (ok != 0) + " errno="
            + err.get(ValueLayout.JAVA_INT, 0));
    }

    static long makeSid(MemorySegment err, byte[] authority6, int... subs) throws Throwable {
        MemorySegment auth = ARENA.allocate(6);
        for (int i = 0; i < 6; i++) {
            auth.set(ValueLayout.JAVA_BYTE, i, authority6[i]);
        }
        int[] s = new int[8];
        for (int i = 0; i < subs.length; i++) {
            s[i] = subs[i];
        }
        MemorySegment slot = ARENA.allocate(ValueLayout.JAVA_LONG);
        int ok = (int) allocSid.invokeExact(err, auth.address(), (byte) subs.length,
            s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], slot.address());
        long sid = slot.get(ValueLayout.JAVA_LONG, 0);
        if (ok == 0) {
            System.out.println("[mx] AllocateAndInitializeSid FAILED errno=" + err.get(ValueLayout.JAVA_INT, 0));
        }
        return sid;
    }

    static String sidToString(MemorySegment err, long sid) throws Throwable {
        MemorySegment out = ARENA.allocate(ValueLayout.JAVA_LONG);
        int ok = (int) convertSidToString.invokeExact(err, sid, out.address());
        long p = out.get(ValueLayout.JAVA_LONG, 0);
        if (ok == 0 || p == 0) {
            return "(ConvertSidToStringSidW errno=" + err.get(ValueLayout.JAVA_INT, 0) + ")";
        }
        String s = MemorySegment.ofAddress(p).reinterpret(512).getString(0, StandardCharsets.UTF_16LE);
        freeLocal(p);
        return s;
    }

    static long makeToken(MemorySegment err, long token, int flags, String label) throws Throwable {
        MemorySegment out = ARENA.allocate(ValueLayout.JAVA_LONG);
        int ok = (int) createRestricted.invokeExact(err, token, flags, 0, 0L, 0, 0L, 0, 0L, out.address());
        long newToken = out.get(ValueLayout.JAVA_LONG, 0);
        System.out.println("[mx] token[" + label + "] flags=0x" + Integer.toHexString(flags) + " ok=" + (ok != 0)
            + " errno=" + err.get(ValueLayout.JAVA_INT, 0) + " token=0x" + Long.toHexString(newToken));
        return newToken;
    }

    static void spawn(String label, MemorySegment err, long token, String cmdline, Path wroteCheck)
            throws Throwable {
        if (token == 0) {
            System.out.println("[mx] " + label + ": SKIP (no token)");
            return;
        }
        MemorySegment cmd = wide(cmdline);
        MemorySegment si = ARENA.allocate(STARTUPINFO_W_SIZE);
        si.set(ValueLayout.JAVA_INT, 0, (int) STARTUPINFO_W_SIZE);
        MemorySegment pi = ARENA.allocate(PROCESS_INFORMATION_SIZE);
        int ok = (int) createProcessAsUser.invokeExact(err, token, 0L, cmd.address(), 0L, 0L,
            0, CREATE_NO_WINDOW, 0L, 0L, si.address(), pi.address());
        if (ok == 0) {
            System.out.println("[mx] " + label + ": CreateProcessAsUser FAILED errno="
                + err.get(ValueLayout.JAVA_INT, 0));
            return;
        }
        long hProcess = pi.get(ValueLayout.JAVA_LONG, 0);
        long hThread = pi.get(ValueLayout.JAVA_LONG, 8);
        int pid = pi.get(ValueLayout.JAVA_INT, 16);
        int waited = (int) waitOne.invokeExact(hProcess, WAIT_TIMEOUT_MS);
        MemorySegment code = ARENA.allocate(ValueLayout.JAVA_INT);
        int got = (int) getExitCode.invokeExact(err, hProcess, code.address());
        int exit = got != 0 ? code.get(ValueLayout.JAVA_INT, 0) : -999;
        int closed = (int) closeHandle.invokeExact(hThread);
        int closed2 = (int) closeHandle.invokeExact(hProcess);
        String wrote = wroteCheck == null ? "-" : String.valueOf(Files.exists(wroteCheck));
        System.out.println("[mx] " + label + ": pid=" + pid + " wait=" + waited + " exit=" + exit
            + " hex=0x" + Integer.toHexString(exit) + " wrote=" + wrote);
    }

    static void freeLocal(long ptr) throws Throwable {
        if (ptr != 0) {
            long ignored = (long) localFree.invokeExact(ptr);
        }
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
        return SymbolLookup.libraryLookup(dll, ARENA);
    }
}
