package io.javanatic.harness.mcp;

import java.util.Objects;

/**
 * 一次 tools/call 的结果（it27）：工具级错误即数据（isError 标记，不抛出——
 * 与 ToolExecutionResult 同形，turn 不因远端工具失败而炸）；传输/协议失败
 * 走异常分档（不躺平、不假装成功）。
 *
 * @param content 模型可见文本（transport 对远端 content 块的文本化呈现）
 * @param isError 远端标记的工具级错误
 */
public record McpCallResult(String content, boolean isError) {

    /** @throws NullPointerException content 为 null 时 */
    public McpCallResult {
        Objects.requireNonNull(content, "content");
    }

    /** @param content 成功输出 */
    public static McpCallResult success(String content) {
        return new McpCallResult(content, false);
    }

    /** @param content 错误说明 */
    public static McpCallResult error(String content) {
        return new McpCallResult(content, true);
    }
}
