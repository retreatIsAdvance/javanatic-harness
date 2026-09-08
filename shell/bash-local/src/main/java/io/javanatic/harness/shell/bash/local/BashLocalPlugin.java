package io.javanatic.harness.shell.bash.local;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.shell.shell.ShellExecutor;

import java.util.Objects;

/**
 * 提供本机 bash 执行（id "shell-bash-local"）。无沙箱、有界直跑：
 * 进程树击杀 + 输出上限 + 超时；真隔离（setsid/landlock）归 sandbox 切片。
 */
public final class BashLocalPlugin implements Plugin {

    /** 数据组合路径的文档化默认（256 KiB 单流上限）。 */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    private final ShellExecutor explicit;

    /** 数据组合路径：maxOutputBytes 从行配置解析。 */
    public BashLocalPlugin() {
        this.explicit = null;
    }

    /** @param options 输出上限等 provider 选项（程序化组合的显式选择） */
    public BashLocalPlugin(BashLocalOptions options) {
        Objects.requireNonNull(options, "options");
        this.explicit = new LocalBashExecutor(options);
    }

    @Override
    public String id() {
        return "shell-bash-local";
    }

    @Override
    public void apply(Scope scope) {
        ShellExecutor executor = explicit;
        if (executor == null) {
            long max = ConfigValues.longValue(
                scope.require(ConfigService.KEY).configFor(id()), id(),
                "maxOutputBytes", DEFAULT_MAX_OUTPUT_BYTES);
            executor = new LocalBashExecutor(new BashLocalOptions(max));
        }
        scope.provide(ShellExecutor.KEY, executor);
    }
}
