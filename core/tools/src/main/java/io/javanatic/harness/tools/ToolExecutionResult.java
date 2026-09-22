package io.javanatic.harness.tools;

import java.util.Objects;

/**
 * 工具执行结果：错误即数据（isError 标记，不抛出——turn 不因工具失败而炸）。
 * concludesTurn 是第三种态：正常成功但终结本 turn（ask_user 类交互工具的停轮
 * 结果；agent-loop 读事件上的同名字段断轮，04 §「数据驱动停 turn」）。
 */
public record ToolExecutionResult(String content, boolean isError, boolean concludesTurn) {

    /** @throws NullPointerException content 为 null 时 */
    public ToolExecutionResult {
        Objects.requireNonNull(content, "content");
    }

    /** @param content 成功输出 */
    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(content, false, false);
    }

    /** @param message 失败说明（进模型可见结果） */
    public static ToolExecutionResult error(String message) {
        return new ToolExecutionResult(message, true, false);
    }

    /** @param e 异常（消息进结果；异常本身不外流） */
    public static ToolExecutionResult error(Exception e) {
        return error(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /**
     * 停轮结果：成功且本 turn 在该 step 后收口（内容仍进模型可见结果——下一轮
     * 请求携带它，回放不重放工具）。
     *
     * @param content 成功输出
     */
    public static ToolExecutionResult concluding(String content) {
        return new ToolExecutionResult(content, false, true);
    }
}
