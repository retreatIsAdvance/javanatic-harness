package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

import java.util.Set;

/**
 * MCP 工具消费插件（id "mcp-tool"，requires "tools" + "mcp-stdio"，it27）：
 * 已连接 server 的工具清单 → 既有 {@code ScopedRegistry} 注册
 * （{@code mcp__<server>__<raw>} 命名，approvalExempt=false 恒真、无豁免出口）
 * + 启动清单落 {@code mcp/catalog} 事件 + JSON Schema → ValueSchema 白名单映射。
 * 本类为脚手架先行（it27 脚手架提交）；注册面与 codec 随停 2 落盘。
 */
public final class McpToolPlugin implements Plugin {

    @Override
    public String id() {
        return "mcp-tool";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools", "mcp-stdio");
    }

    @Override
    public void apply(Scope scope) {
        // 停 2：工具注册 + mcp/catalog 落账 + schema 映射
    }
}
