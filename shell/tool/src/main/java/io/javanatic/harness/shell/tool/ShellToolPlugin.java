package io.javanatic.harness.shell.tool;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.shell.shell.ShellExecutor;
import io.javanatic.harness.shell.shell.ShellRequest;
import io.javanatic.harness.shell.shell.ShellResult;
import io.javanatic.harness.tools.RenderIntent;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * shell Consumer（id "shell-tool"，requires "tools" + "shell-bash-local"）：
 * 把 bash 注册为工具。工作目录与超时是组合身份（构造注入），模型只递命令——
 * timeout 不暴露为模型参数（schema 仅 required 词表；模型影响力有界）。
 * 全部经 ToolExecutor pipeline（审批/落账是 executor 的 stage）。
 */
public final class ShellToolPlugin implements Plugin {

    private static final ValueSchema.Str COMMAND = new ValueSchema.Str("要执行的 bash 命令");

    private final Path workspace;
    private final Duration timeout;

    /**
     * @param workspace 工作目录（绝对路径；agent 在哪执行命令由组合决定）
     * @param timeout   每条命令的超时（组合期上限）
     */
    public ShellToolPlugin(Path workspace, Duration timeout) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        if (!workspace.isAbsolute()) {
            throw new IllegalArgumentException("workspace must be absolute: " + workspace);
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public String id() {
        return "shell-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "shell-bash-local");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        ShellExecutor shell = scope.require(ShellExecutor.KEY);
        ToolDefinition bash = new ToolDefinition("bash", "在工作目录执行 bash 命令",
            new ValueSchema.Object("参数", Map.of("command", COMMAND)),
            RenderIntent.TERMINAL,
            (args, ctx) -> {
                ShellResult result = shell.execute(
                    new ShellRequest(args.readString("command"), workspace, timeout, Map.of()),
                    ctx.signal());
                return result.exitCode() == 0
                    ? ToolExecutionResult.success(format(result))
                    : ToolExecutionResult.error(format(result));
            });
        scope.onClose(registry.register(bash));
    }

    private static String format(ShellResult result) {
        StringBuilder sb = new StringBuilder("exit: ").append(result.exitCode());
        if (result.outputTruncated()) {
            sb.append(" (output truncated)");
        }
        sb.append("\n\nstdout:\n").append(result.stdout());
        if (!result.stderr().isEmpty()) {
            sb.append("\nstderr:\n").append(result.stderr());
        }
        return sb.toString();
    }
}
