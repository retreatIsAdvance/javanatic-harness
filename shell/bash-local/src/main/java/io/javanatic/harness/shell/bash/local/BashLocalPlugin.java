package io.javanatic.harness.shell.bash.local;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.shell.shell.ShellExecutor;

import java.util.Objects;

/**
 * 提供本机 bash 执行（id "shell-bash-local"）。无沙箱、有界直跑：
 * 进程树击杀 + 输出上限 + 超时；真隔离（setsid/landlock）归 sandbox 切片。
 */
public final class BashLocalPlugin implements Plugin {

    private final ShellExecutor executor;

    /** @param options 输出上限等 provider 选项（组合期显式选择） */
    public BashLocalPlugin(BashLocalOptions options) {
        Objects.requireNonNull(options, "options");
        this.executor = new LocalBashExecutor(options);
    }

    @Override
    public String id() {
        return "shell-bash-local";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(ShellExecutor.KEY, executor);
    }
}
