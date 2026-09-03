package io.javanatic.harness.tools;


import java.util.Objects;

/**
 * 一个工具：schema、渲染意图与执行函数。执行函数只产出
 * {@link ToolExecutionResult}——落账与审批在 ToolExecutor（R2/R4），
 * 工具在结构上无法「执行了但不留痕」。
 */
public record ToolDefinition(String name, String description, ValueSchema parameters,
                             RenderIntent render, Tool tool) {

    /** @throws NullPointerException 任一字段为 null 或 name 为空时 */
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(render, "render");
        Objects.requireNonNull(tool, "tool");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("tool name must be non-empty");
        }
    }

    /** 工具执行函数（异常由 executor 转 error result；AbortedException 传播）。 */
    @FunctionalInterface
    public interface Tool {
        /** @param args 已按 schema 校验的实参 */
        ToolExecutionResult execute(ToolArgs args, ToolExecutionContext context) throws Exception;
    }

    /** 便捷工厂（渲染 GENERIC）。 */
    public static ToolDefinition of(String name, String description, ValueSchema parameters, Tool tool) {
        return new ToolDefinition(name, description, parameters, RenderIntent.GENERIC, tool);
    }
}
