package io.javanatic.harness.todo;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.tools.ToolArgs;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ValueSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * todo Consumer（id "todo"，requires "tools"）：todo_write 整表替换工具 + 每次
 * 调用一条全量快照事件（dsh tool-todo 形状）。allowParallelInProgress 是部署
 * 必选项——是否允许多条同时 in_progress（并行 subagent 场景 true；单 agent
 * 顺序工作 false，多条即拒）。
 *
 * <p>落账走 {@link io.javanatic.harness.tools.ToolExecutionContext#session()}
 * 直写（解析/校验/落账一处完成，「落账 = 模型所写」结构性成立）；审计对
 * (tool/call + tool/result) 仍归 executor。codec 经 ServiceLoader 由持久化层
 * 发现，与插件装载无行序依赖。
 */
public final class TodoPlugin implements Plugin {

    private static final ValueSchema.Str CONTENT =
        new ValueSchema.Str("What the task is — a short imperative line.");
    private static final ValueSchema.Str STATUS =
        new ValueSchema.Str("pending (not started) | in_progress (now) | completed (done).");
    private static final ValueSchema.Object ITEM =
        new ValueSchema.Object("One task", Map.of("content", CONTENT, "status", STATUS));

    private final Boolean explicitAllowParallel;

    /** 数据组合路径：allowParallelInProgress 从行配置解析（必配，缺失 fail loud）。 */
    public TodoPlugin() {
        this.explicitAllowParallel = null;
    }

    /** @param allowParallelInProgress 是否允许多条同时 in_progress（程序化组合的显式选择） */
    public TodoPlugin(boolean allowParallelInProgress) {
        this.explicitAllowParallel = allowParallelInProgress;
    }

    @Override
    public String id() {
        return "todo";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public void apply(Scope scope) {
        boolean allowParallel = explicitAllowParallel != null
            ? explicitAllowParallel
            : ConfigValues.requireBool(
                scope.require(ConfigService.KEY).configFor(id()), id(), "allowParallelInProgress");
        ToolRegistry registry = scope.require(ToolRegistry.KEY);
        scope.onClose(registry.register(scope, tool(allowParallel)));
    }

    private static ToolDefinition tool(boolean allowParallel) {
        return ToolDefinition.of("todo_write", describe(allowParallel),
            new ValueSchema.Object("参数", Map.of("todos",
                new ValueSchema.Arr("The COMPLETE task list, replacing any previous list.", ITEM))),
            (args, context) -> {
                List<TodoItem> todos = canonicalize(args.readList("todos"), allowParallel);
                context.session().append(new TodoWriteEvent(System.currentTimeMillis(), todos));
                return ToolExecutionResult.success("Updated todo list: "
                    + countBy(todos, TodoStatus.PENDING) + " pending, "
                    + countBy(todos, TodoStatus.IN_PROGRESS) + " in progress, "
                    + countBy(todos, TodoStatus.COMPLETED) + " completed.");
            });
    }

    /**
     * 值约束（schema 表达不了的部分）：content trim 非空、不重复、status 枚举
     * 收窄、非并行部署 ≤1 条 in_progress。违规抛 IllegalArgumentException——
     * executor 转 error result（错误即数据，dsh 同款消息形状）。
     */
    private static List<TodoItem> canonicalize(List<ToolArgs> raw, boolean allowParallel) {
        List<TodoItem> todos = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        int inProgress = 0;
        for (ToolArgs item : raw) {
            String content = item.readString("content").trim();
            if (content.isEmpty()) {
                throw new IllegalArgumentException("invalid todos: content must be a non-empty string");
            }
            if (!seen.add(content)) {
                throw new IllegalArgumentException("invalid todos: duplicate content \"" + content + "\"");
            }
            TodoStatus status = TodoStatus.fromWire(item.readString("status"));
            if (status == TodoStatus.IN_PROGRESS) {
                inProgress++;
            }
            todos.add(new TodoItem(content, status));
        }
        if (!allowParallel && inProgress > 1) {
            throw new IllegalArgumentException(
                "invalid todos: at most one task may be in_progress (got " + inProgress + ")");
        }
        return todos;
    }

    private static int countBy(List<TodoItem> todos, TodoStatus status) {
        return (int) todos.stream().filter(todo -> todo.status() == status).count();
    }

    /** 模型可见说明：整表替换语义 + 并行策略是唯一随部署变化的段落。 */
    private static String describe(boolean allowParallel) {
        String head = "Record and update a structured task list for the current work. Send the ENTIRE "
            + "list every call — it REPLACES the previous list (no partial updates, no per-item edits). "
            + "Add one todo per concrete step before you start. ";
        String active = allowParallel
            ? "Mark every todo being actively worked on in_progress — several at once when work "
                + "genuinely runs in parallel, one for sequential work. "
            : "Keep AT MOST ONE todo in_progress at a time; while work remains, exactly one "
                + "active task should be in_progress. ";
        String tail = "Mark a todo completed the moment it is done (do not batch completions). "
            + "Skip the list for trivial single-step tasks. Statuses: pending | in_progress | completed.";
        return head + active + tail;
    }
}
