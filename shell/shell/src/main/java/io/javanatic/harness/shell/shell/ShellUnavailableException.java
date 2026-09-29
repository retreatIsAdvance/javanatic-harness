package io.javanatic.harness.shell.shell;

/**
 * 宿主上解释器不可用，provider 无法构造可执行 argv。fail-closed：宁可拒绝执行
 * 也不静默兜底（方言不同的替代解释器会让引号/编码/退出码语义悄悄漂移）。
 * 首个场景：Windows 宿主未装 pwsh（Windows PowerShell 5.1 不代跑）。
 */
public final class ShellUnavailableException extends RuntimeException {

    /** @param message 诊断（命名缺失解释器与安装指引） */
    public ShellUnavailableException(String message) {
        super(message);
    }
}
