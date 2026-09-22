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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * fs Consumer（id "fs-tool"，requires "tools" + "sandbox-policy"）：把 FsService 的
 * 五个操作注册为工具，全部经 ToolExecutor 执行（审批/落账是 executor 的 stage，
 * 不是工具的自觉）。进程内文件围栏：READ_ONLY（如计划模式）下变异工具直接拒——
 * 与 Seatbelt 共用 WritableRoots 语义的「模式级」消费端强制；WORKSPACE_WRITE 的
 * 路径边界由 fs-local root 与 sandbox workspace 对齐保证，漂移在装配期 fail loud
 * （it21 四键断言；不再是「交集生效」的静默语义）。
 * 注册凭据挂自身 scope（R3）。
 *
 * <p>编辑面可靠性（it21）：编辑须唯一匹配（多处即拒，见 FsService.edit）；
 * 编辑/写入前经 {@link ReadLedger} 折叠的读后事实与磁盘比对，外部修改即拒；
 * 删除不设读后守卫（已记录边界，见 05 §4）。
 */
public final class FsToolPlugin implements Plugin {

    private static final ValueSchema.Str PATH = new ValueSchema.Str("绝对或相对路径");
    private static final ValueSchema.Str CONTENT = new ValueSchema.Str("写入内容");
    private static final ValueSchema.Str OLD = new ValueSchema.Str("被替换的原文（须唯一匹配：0 或 ≥2 处即拒绝）");
    private static final ValueSchema.Str NEW = new ValueSchema.Str("替换后的新文");

    // 实参 schema：工具注册与 ReadLedger 的历史实参解析共用（同一事实两处消费，
    // 常量同源——schema 漂移会让折叠解析错位）
    static final ValueSchema READ_ARGS =
        new ValueSchema.Object("参数", Map.of("path", PATH));
    static final ValueSchema WRITE_ARGS =
        new ValueSchema.Object("参数", Map.of("path", PATH, "content", CONTENT));
    static final ValueSchema EDIT_ARGS =
        new ValueSchema.Object("参数", Map.of("path", PATH, "old_string", OLD, "new_string", NEW));

    /** 列举截断尾行（it20）：条目达到上限时追加，模型面可见。 */
    private static final String LIST_TRUNCATED_TAIL = "… (list truncated)";

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

    /**
     * 读后修改保护（it21）：日志折叠出的最新内容事实与磁盘当前内容不一致即拒——
     * 无事实（从未成功读过 / 只读到截断内容 / 路径写法不同源）或当前内容读不到
     * （文件缺失等）即放行，交给底层操作给出原生错误。
     */
    private static ToolExecutionResult guard(FsService fs, ToolExecutionContext ctx, Path path,
                                             Mutation op) throws Exception {
        String fact = ReadLedger.fold(ctx.session().events()).factFor(path);
        if (fact != null) {
            String current;
            try {
                current = fs.read(path);
            } catch (IOException e) {
                return op.run();
            }
            if (!current.equals(fact)) {
                return ToolExecutionResult.error("file changed since read: " + path
                    + " — the content logged at the last read no longer matches disk; re-read it, then retry");
            }
        }
        return op.run();
    }

    private static ToolDefinition readTool(FsService fs) {
        return ToolDefinition.of("fs_read", "读取文件内容", READ_ARGS,
            (args, ctx) -> ToolExecutionResult.success(
                fs.read(Path.of(args.readString("path")))));
    }

    private static ToolDefinition writeTool(FsService fs, SandboxPolicyService policies) {
        return ToolDefinition.of("fs_write", "写入文件（覆盖）", WRITE_ARGS,
            (args, ctx) -> mutate(policies, ctx, () -> {
                Path path = Path.of(args.readString("path"));
                return guard(fs, ctx, path, () -> {
                    fs.write(path, args.readString("content"));
                    return ToolExecutionResult.success("written");
                });
            }));
    }

    private static ToolDefinition editTool(FsService fs, SandboxPolicyService policies) {
        return ToolDefinition.of("fs_edit", "精确替换文件中唯一匹配的文本（0 或 ≥2 处匹配即拒绝）",
            EDIT_ARGS,
            (args, ctx) -> mutate(policies, ctx, () -> {
                Path path = Path.of(args.readString("path"));
                return guard(fs, ctx, path, () -> ToolExecutionResult.success(fs.edit(path,
                    args.readString("old_string"), args.readString("new_string"))));
            }));
    }

    private static ToolDefinition deleteTool(FsService fs, SandboxPolicyService policies) {
        // 无读后守卫(P5 裁 edit/write):删除读后被外部改动的文件仍是静默销毁——
        // 已记录边界(05 §4),实撞再补 guard + 一测
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
            (args, ctx) -> {
                FsService.Listing listing = fs.list(Path.of(args.readString("path")));
                String entries = listing.entries().stream()
                    .map(e -> (e.directory() ? "d " : "f ") + e.name())
                    .collect(Collectors.joining("\n"));
                return ToolExecutionResult.success(
                    listing.truncated() ? entries + "\n" + LIST_TRUNCATED_TAIL : entries);
            });
    }
}
