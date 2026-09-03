package io.javanatic.harness.tools;

import java.util.Objects;

/** 工具执行结果：错误即数据（isError 标记，不抛出——turn 不因工具失败而炸）。 */
public record ToolExecutionResult(String content, boolean isError) {

    /** @throws NullPointerException content 为 null 时 */
    public ToolExecutionResult {
        Objects.requireNonNull(content, "content");
    }

    /** @param content 成功输出 */
    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(content, false);
    }

    /** @param message 失败说明（进模型可见结果） */
    public static ToolExecutionResult error(String message) {
        return new ToolExecutionResult(message, true);
    }

    /** @param e 异常（消息进结果；异常本身不外流） */
    public static ToolExecutionResult error(Exception e) {
        return error(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
