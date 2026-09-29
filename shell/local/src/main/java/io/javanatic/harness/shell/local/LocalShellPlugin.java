package io.javanatic.harness.shell.local;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.sandbox.sandbox.SandboxProvider;
import io.javanatic.harness.shell.shell.ShellExecutor;

import java.util.Objects;

/**
 * 提供本机 shell 执行（id "shell-local"；argv 按平台分派，见 {@link ShellPlatform}）。
 * 有界直跑 + 进程树击杀 + 输出上限 + 超时；受限策略经 SandboxProvider.confine 包装
 * argv（provider 缺席则受限请求 fail-closed——文件效果边界归 sandbox 切片）。
 */
public final class LocalShellPlugin implements Plugin {

    /** 数据组合路径的文档化默认（256 KiB 单流上限）。 */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    private final LocalShellOptions explicitOptions;

    /** 数据组合路径：maxOutputBytes 从行配置解析。 */
    public LocalShellPlugin() {
        this.explicitOptions = null;
    }

    /** @param options 输出上限等 provider 选项（程序化组合的显式选择） */
    public LocalShellPlugin(LocalShellOptions options) {
        this.explicitOptions = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return "shell-local";
    }

    @Override
    public void apply(Scope scope) {
        // 两条装配路径等价：沙箱 provider 都在 apply 时解析（缺席则受限请求 fail-closed）
        LocalShellOptions options = explicitOptions;
        if (options == null) {
            options = new LocalShellOptions(ConfigValues.longValue(
                scope.require(ConfigService.KEY).configFor(id()), id(),
                "maxOutputBytes", DEFAULT_MAX_OUTPUT_BYTES));
        }
        scope.provide(ShellExecutor.KEY,
            new LocalShellExecutor(options, scope.resolve(SandboxProvider.KEY).orElse(null)));
    }
}
