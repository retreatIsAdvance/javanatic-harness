package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * fs Consumer（id "fs-tool"，requires "tools"）：把 FsService 的五个操作注册为
 * 工具，全部经 ToolExecutor 执行（审批/落账是 executor 的 stage，不是工具的自觉）。
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
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        FsService fs = scope.require(FsService.KEY);
        scope.onClose(registry.register(readTool(fs)));
        scope.onClose(registry.register(writeTool(fs)));
        scope.onClose(registry.register(editTool(fs)));
        scope.onClose(registry.register(deleteTool(fs)));
        scope.onClose(registry.register(listTool(fs)));
    }

    private static ToolDefinition readTool(FsService fs) {
        return ToolDefinition.of("fs_read", "读取文件内容",
            new ValueSchema.Object("参数", Map.of("path", PATH)),
            (args, ctx) -> io.javanatic.harness.tools.ToolExecutionResult.success(
                fs.read(Path.of(args.readString("path")))));
    }

    private static ToolDefinition writeTool(FsService fs) {
        return ToolDefinition.of("fs_write", "写入文件（覆盖）",
            new ValueSchema.Object("参数", Map.of("path", PATH, "content", CONTENT)),
            (args, ctx) -> {
                fs.write(Path.of(args.readString("path")), args.readString("content"));
                return io.javanatic.harness.tools.ToolExecutionResult.success("written");
            });
    }

    private static ToolDefinition editTool(FsService fs) {
        return ToolDefinition.of("fs_edit", "精确替换文件中第一处匹配文本",
            new ValueSchema.Object("参数", Map.of("path", PATH, "old_string", OLD, "new_string", NEW)),
            (args, ctx) -> io.javanatic.harness.tools.ToolExecutionResult.success(
                fs.edit(Path.of(args.readString("path")),
                    args.readString("old_string"), args.readString("new_string"))));
    }

    private static ToolDefinition deleteTool(FsService fs) {
        return ToolDefinition.of("fs_delete", "删除文件",
            new ValueSchema.Object("参数", Map.of("path", PATH)),
            (args, ctx) -> {
                fs.delete(Path.of(args.readString("path")));
                return io.javanatic.harness.tools.ToolExecutionResult.success("deleted");
            });
    }

    private static ToolDefinition listTool(FsService fs) {
        return ToolDefinition.of("fs_list", "列出目录条目",
            new ValueSchema.Object("参数", Map.of("path", PATH)),
            (args, ctx) -> io.javanatic.harness.tools.ToolExecutionResult.success(
                fs.list(Path.of(args.readString("path"))).stream()
                    .map(e -> (e.directory() ? "d " : "f ") + e.name())
                    .collect(Collectors.joining("\n"))));
    }
}
