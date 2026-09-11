package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.sandbox.sandbox.SandboxMode;
import io.javanatic.harness.sandbox.sandbox.SandboxPolicyService;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionContext;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * fs Consumer（id "fs-tool"，requires "tools" + "sandbox-policy"）：把 FsService 的
 * 五个操作注册为工具，全部经 ToolExecutor 执行（审批/落账是 executor 的 stage，
 * 不是工具的自觉）。进程内文件围栏：READ_ONLY（如计划模式）下变异工具直接拒——
 * 与 Seatbelt 共用 WritableRoots 语义的「模式级」消费端强制（WORKSPACE_WRITE 的
 * 路径边界由 fs-local root 与 sandbox workspace 对齐保证，漂移=交集生效）。
 * 注册凭据挂自身 scope（R3）。
 */
public final class FsToolPlugin implements Plugin {

    private static final ValueSchema.Str PATH = new ValueSchema.Str("绝对或相对路径");
    private static final ValueSchema.Str CONTENT = new ValueSchema.Str("写入内容");
    private static final ValueSchema.Str OLD = new ValueSchema.Str("被替换的原文（须唯一匹配）");
    private static final ValueSchema.Str NEW = new ValueSchema.Str("替换后的新文");

    @Override
    public String id() {
        return "fs-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "sandbox-policy");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        FsService fs = scope.require(FsService.KEY);
        SandboxPolicyService policies = scope.require(SandboxPolicyService.KEY);
        scope.onClose(registry.register(scope, readTool(fs)));
        scope.onClose(registry.register(scope, writeTool(fs, policies)));
        scope.onClose(registry.register(scope, editTool(fs, policies)));
        scope.onClose(registry.register(scope, deleteTool(fs, policies)));
        scope.onClose(registry.register(scope, listTool(fs)));
    }

    /** 变异操作体（fs 服务抛 IOException——executor 转 error result）。 */
    @FunctionalInterface
    private interface Mutation {
        ToolExecutionResult run() throws Exception;
    }

    /** 只读围栏：READ_ONLY（含计划模式）下变异操作拒绝——错误即数据。 */
    private static ToolExecutionResult mutate(SandboxPolicyService policies,
                                              ToolExecutionContext ctx, Mutation op) throws Exception {
        if (policies.resolve(ctx.session()).mode() == SandboxMode.READ_ONLY) {
            return ToolExecutionResult.error(
                "denied by sandbox: the session is read-only (plan mode) — propose the change instead of writing it");
        }
        return op.run();
    }

    private static ToolDefinition readTool(FsService fs) {
        return ToolDefinition.of("fs_read", "读取文件内容",
            new ValueSchema.Object("参数", Map.of("path", PATH)),
            (args, ctx) -> ToolExecutionResult.success(
                fs.read(Path.of(args.readString("path")))));
    }

    private static ToolDefinition writeTool(FsService fs, SandboxPolicyService policies) {
        return ToolDefinition.of("fs_write", "写入文件（覆盖）",
            new ValueSchema.Object("参数", Map.of("path", PATH, "content", CONTENT)),
            (args, ctx) -> mutate(policies, ctx, () -> {
                fs.write(Path.of(args.readString("path")), args.readString("content"));
                return ToolExecutionResult.success("written");
            }));
    }

    private static ToolDefinition editTool(FsService fs, SandboxPolicyService policies) {
        return ToolDefinition.of("fs_edit", "精确替换文件中第一处匹配文本",
            new ValueSchema.Object("参数", Map.of("path", PATH, "old_string", OLD, "new_string", NEW)),
            (args, ctx) -> mutate(policies, ctx, () -> ToolExecutionResult.success(
                fs.edit(Path.of(args.readString("path")),
                    args.readString("old_string"), args.readString("new_string")))));
    }

    private static ToolDefinition deleteTool(FsService fs, SandboxPolicyService policies) {
        return ToolDefinition.of("fs_delete", "删除文件",
            new ValueSchema.Object("参数", Map.of("path", PATH)),
            (args, ctx) -> mutate(policies, ctx, () -> {
                fs.delete(Path.of(args.readString("path")));
                return ToolExecutionResult.success("deleted");
            }));
    }

    private static ToolDefinition listTool(FsService fs) {
        return ToolDefinition.of("fs_list", "列出目录条目",
            new ValueSchema.Object("参数", Map.of("path", PATH)),
            (args, ctx) -> ToolExecutionResult.success(
                fs.list(Path.of(args.readString("path"))).stream()
                    .map(e -> (e.directory() ? "d " : "f ") + e.name())
                    .collect(Collectors.joining("\n"))));
    }
}
