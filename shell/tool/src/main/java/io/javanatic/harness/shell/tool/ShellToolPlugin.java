package io.javanatic.harness.shell.tool;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicy;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;
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
 * shell Consumer（id "shell-tool"，requires "tools" + "shell-bash-local"
 * + "sandbox-policy"）：把 bash 注册为工具。工作目录与超时是组合身份（构造注入），
 * 模型只递命令——timeout 不暴露为模型参数（schema 仅 required 词表；模型影响力
 * 有界）。文件效果策略逐调用解析（SandboxPolicyService——含计划模式压只读），
 * 拒绝时结果带沙箱标记（模型能分辨「被沙箱拒」与「命令失败」）。
 * 全部经 ToolExecutor pipeline（审批/落账是 executor 的 stage）。
 */
public final class ShellToolPlugin implements Plugin {

    private static final ValueSchema.Str COMMAND = new ValueSchema.Str("要执行的 bash 命令");

    /** 数据组合路径的超时文档化默认（60s）。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final Path explicitWorkspace;
    private final Duration explicitTimeout;

    /** 数据组合路径：workspace 必填（命令执行边界无默认）、timeoutSeconds 默认 60。 */
    public ShellToolPlugin() {
        this.explicitWorkspace = null;
        this.explicitTimeout = null;
    }

    /**
     * @param workspace 工作目录（绝对路径；agent 在哪执行命令由组合决定）
     * @param timeout   每条命令的超时（组合期上限）
     */
    public ShellToolPlugin(Path workspace, Duration timeout) {
        this.explicitWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (!workspace.isAbsolute()) {
            throw new IllegalArgumentException("workspace must be absolute: " + workspace);
        }
        this.explicitTimeout = Objects.requireNonNull(timeout, "timeout");
    }

    private Path workspace(Scope scope) {
        if (explicitWorkspace != null) {
            return explicitWorkspace;
        }
        return Path.of(ConfigValues.requireString(
            scope.require(ConfigService.KEY).configFor(id()), id(), "workspace"));
    }

    private Duration timeout(Scope scope) {
        if (explicitTimeout != null) {
            return explicitTimeout;
        }
        return Duration.ofSeconds(ConfigValues.longValue(
            scope.require(ConfigService.KEY).configFor(id()), id(),
            "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
    }

    @Override
    public String id() {
        return "shell-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "shell-bash-local", "sandbox-policy");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        ShellExecutor shell = scope.require(ShellExecutor.KEY);
        SandboxPolicyService policies = scope.require(SandboxPolicyService.KEY);
        ToolDefinition bash = new ToolDefinition("bash", "在工作目录执行 bash 命令",
            new ValueSchema.Object("参数", Map.of("command", COMMAND)),
            RenderIntent.TERMINAL,
            (args, ctx) -> {
                SandboxPolicy policy = policies.resolve(ctx.session());
                ShellResult result = shell.execute(
                    new ShellRequest(args.readString("command"),
                        workspace(scope), timeout(scope), Map.of(), policy),
                    ctx.signal());
                return result.exitCode() == 0
                    ? ToolExecutionResult.success(format(result))
                    : ToolExecutionResult.error(format(result));
            });
        scope.onClose(registry.register(scope, bash));
    }

    private static String format(ShellResult result) {
        StringBuilder sb = new StringBuilder("exit: ").append(result.exitCode());
        if (result.outputTruncated()) {
            sb.append(" (output truncated)");
        }
        if (result.sandboxDenied()) {
            sb.append(" [sandbox: a file effect was denied by the sandbox policy]");
        }
        sb.append("\n\nstdout:\n").append(result.stdout());
        if (!result.stderr().isEmpty()) {
            sb.append("\nstderr:\n").append(result.stderr());
        }
        return sb.toString();
    }
}
