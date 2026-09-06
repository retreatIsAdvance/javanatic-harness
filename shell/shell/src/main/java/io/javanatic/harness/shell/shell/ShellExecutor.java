package io.javanatic.harness.shell.shell;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.AbortSignal;

/**
 * 命令执行能力入口（Definition）。Consumer（shell-tool）不 import Provider，
 * 组合换 Provider 即换执行环境（本机 bash → 容器 → 远程）。
 */
public interface ShellExecutor {

    /** 本服务的服务键。 */
    ServiceKey<ShellExecutor> KEY = new ServiceKey<>("shell");

    /**
     * 执行一条 shell 命令。
     *
     * @param request 命令与执行参数
     * @param signal  取消信号（取消 = 击杀进程树并抛 AbortedException）
     * @return 执行结果（stdout/stderr 超出上限时截断并置 truncated 标记）
     * @throws java.util.concurrent.TimeoutException 超时（进程树已击杀）
     * @throws io.javanatic.harness.llm.AbortedException 取消（进程树已击杀）
     */
    ShellResult execute(ShellRequest request, AbortSignal signal) throws Exception;
}
