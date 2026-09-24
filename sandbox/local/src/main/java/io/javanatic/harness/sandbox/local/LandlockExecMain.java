package io.javanatic.harness.sandbox.local;

import java.util.List;

/**
 * Landlock 助手入口（it24）：sandbox-local 的 linux 第二候选以「本镜像自带的
 * JVM + 本类」实现自限制后 exec（见 {@link Landlock} 类注）——启动命令由
 * {@link Landlock#hostHelperCommand()} 按运行事实三选一（镜像 {@code -m} /
 * 模块路径 / classpath），目标 argv 由 {@link Landlock#runArgs} 逐参传递。
 *
 * <p>用法：{@code LandlockExecMain --probe} 或
 * {@code LandlockExecMain --mode read-only|workspace-write [--root <path>]... -- <command> [args...]}。
 * 退出码协议见 {@link Landlock} 的 EXIT_* 常量；任何非零都是 fail-closed，
 * 诊断（含 fail 原因）在 stderr——provider 首探缓存取最后一行作 detail。
 */
public final class LandlockExecMain {

    private LandlockExecMain() {
    }

    /**
     * @param args 助手指令（不含 JVM 参数——JVM 开关由启动命令固定携带）
     */
    public static void main(String[] args) {
        Landlock.Invocation invocation = Landlock.parse(List.of(args)).orElse(null);
        if (invocation == null) {
            System.err.println(Landlock.USAGE);
            System.exit(Landlock.EXIT_USAGE);
        }
        int exit = invocation.probe() ? Landlock.probe(System.err) : Landlock.run(invocation, System.err);
        System.exit(exit);
    }
}
