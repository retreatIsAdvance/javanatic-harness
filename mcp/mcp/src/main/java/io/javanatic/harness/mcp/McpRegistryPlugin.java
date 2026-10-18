package io.javanatic.harness.mcp;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/**
 * MCP 连接注册表插件（id "mcp-registry"，it27.1）：把 {@link McpRegistry} 以
 * {@link McpClientService#KEY} 提供进共享 scope。注册表构造入口在本模块内
 * （构造者须同模块，对齐 SkillPlugin / ToolsPlugin 先例）；两个 transport
 * 提供者都向它注册，消费面只见注册表——不再各自 provide 同一 key。
 */
public final class McpRegistryPlugin implements Plugin {

    @Override
    public String id() {
        return "mcp-registry";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(McpClientService.KEY, new McpRegistry());
    }
}
