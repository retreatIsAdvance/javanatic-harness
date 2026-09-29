package io.javanatic.harness.sandbox.local;

import java.util.List;

/**
 * windows-acl 助手入口（it25 S-a）：sandbox-local 的 win32 唯一候选以「本镜像自带的
 * JVM + 本类」实现——构造低完整性令牌、递归打标、再以令牌 CreateProcessAsUserW 生子
 * （见 {@link WindowsAcl} 类注）。启动命令由 {@link WindowsAcl#hostHelperCommand()}
 * 按运行事实构造，目标 argv 由 {@link WindowsAcl#runArgs} 逐参传递。
 *
 * <p>用法：{@code WindowsAclExecMain --probe} 或
 * {@code WindowsAclExecMain --mode read-only --argv-b64 <base64>} 或
 * {@code WindowsAclExecMain --mode workspace-write --temp <dir> [--root <path>]... --argv-b64 <base64>}
 * （argv 单参载体格式见 {@link WindowsAcl#encodeArgv}）。
 * 退出码协议见 {@link WindowsAcl} 的 EXIT_* 常量；任何非零都是 fail-closed，
 * 诊断（含 fail 原因）在 stderr——provider 首探缓存取最后一行作 detail。
 */
public final class WindowsAclExecMain {

    private WindowsAclExecMain() {
    }

    /**
     * @param args 助手指令（不含 JVM 参数——JVM 开关由启动命令固定携带）
     */
    public static void main(String[] args) {
        WindowsAcl.Invocation invocation = WindowsAcl.parse(List.of(args)).orElse(null);
        if (invocation == null) {
            System.err.println(WindowsAcl.USAGE);
            System.exit(WindowsAcl.EXIT_USAGE);
        }
        int exit = invocation.probe() ? WindowsAcl.probe(System.err) : WindowsAcl.run(invocation, System.err);
        System.exit(exit);
    }
}
