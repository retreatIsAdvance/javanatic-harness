import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** Landlock.Native 的五个 FFM 形状按原样复刻，在 darwin 上验证绑定机制。 */
public class ShapeProbe {
    static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    static final MemoryLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
    static final long ERRNO_OFFSET = CAPTURE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("errno"));

    static MethodHandle variadic(String symbol, FunctionDescriptor d, int first) {
        return Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find(symbol).orElseThrow(),
            d, CAPTURE_ERRNO, Linker.Option.firstVariadicArg(first));
    }

    static int errnoOf(MemorySegment capture) {
        return capture.get(ValueLayout.JAVA_INT, (int) ERRNO_OFFSET);
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("capture layout=" + CAPTURE_LAYOUT + " errnoOffset=" + ERRNO_OFFSET);
        Linker linker = Linker.nativeLinker();
        for (String symbol : new String[] {"syscall", "prctl", "open", "close", "execvp"}) {
            System.out.println("symbol " + symbol + " present=" + linker.defaultLookup().find(symbol).isPresent());
        }

        // (a) SYS form: syscall 描述符 (5×JAVA_LONG, firstVariadicArg(1)) + errno capture
        MethodHandle syscall = variadic("syscall",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), 1);
        long pid;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            pid = (long) syscall.invokeExact(capture, 20L, 0L, 0L, 0L, 0L);   // darwin SYS_getpid=20
            System.out.println("(a) syscall(20)=pid " + pid + " (self=" + ProcessHandle.current().pid()
                + ") errno=" + errnoOf(capture));
        }

        // (b) OPEN form: (ADDRESS, INT, INT) firstVariadicArg(2) + capture
        MethodHandle open = variadic("open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT), 2);
        int fd;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            fd = (int) open.invokeExact(capture, arena.allocateFrom("/"), 0, 0);
            System.out.println("(b) open(\"/\") fd=" + fd + " errno=" + errnoOf(capture));
        }

        // (c) CLOSE form: 无 capture 的普通绑定
        MethodHandle close = linker.downcallHandle(linker.defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        System.out.println("(c) close(fd) rc=" + (int) close.invokeExact(fd));

        // (d) PRCTL form: (INT, 4×JAVA_LONG) firstVariadicArg(1) + capture —— Linux-only 符号面
        //     先单独探：darwin 无 prctl 则只记录，不绑定
        System.out.println("(d) prctl bindable on this host: " + linker.defaultLookup().find("prctl").isPresent());

        // (e) EXECVP form: (ADDRESS, ADDRESS) + capture（真替换进程；成功则不返回）
        MethodHandle execvp = linker.downcallHandle(linker.defaultLookup().find("execvp").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE_ERRNO);
        if (args.length > 0 && args[0].equals("exec")) {
            String[] argv = {"/bin/echo", "jh-execvp-ok"};
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment file = arena.allocateFrom(argv[0]);
                MemorySegment pointers = arena.allocate(ValueLayout.ADDRESS, argv.length + 1L);
                for (int i = 0; i < argv.length; i++) {
                    pointers.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(argv[i]));
                }
                pointers.setAtIndex(ValueLayout.ADDRESS, argv.length, MemorySegment.NULL);
                MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
                int rc = (int) execvp.invokeExact(capture, file, pointers);
                System.out.println("(e) execvp returned rc=" + rc + " errno=" + errnoOf(capture));
            }
        } else {
            System.out.println("(e) execvp bound; run with arg 'exec' to真替换进程");
        }

        // (f) 反例（S-b 补录；形状错=静默传错参）：把 capture 布局也写进描述符、并让它占住一个参数槽
        //     ——绑定与 invokeExact 均不报错；原生 open 收到错位实参，静默返回 -1（六次实跑 errno 在
        //     14/EFAULT 与 22/EINVAL 间摆动——错位槽收到什么由运行期地址决定）——错形状只能靠真实调用暴露。
        MethodHandle openWrong = variadic("open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, CAPTURE_LAYOUT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), 3);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            int rc = (int) openWrong.invokeExact(capture, capture, arena.allocateFrom("/"), 0, 0);
            System.out.println("(f) wrong-shape open rc=" + rc + " errno=" + errnoOf(capture)
                + " (EFAULT=14)");
        }
    }
}
