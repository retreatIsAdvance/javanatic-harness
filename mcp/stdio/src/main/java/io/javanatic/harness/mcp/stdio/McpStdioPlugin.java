package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/**
 * MCP stdio 供应插件（id "mcp-stdio"，it27）：装配期解析行配置 servers[]
 * （id / command / args / env 白名单 / timeoutSeconds），拉起全部已配 server
 * 子进程（哪怕本轮不用；成本口径 07），以 McpClientService.KEY 提供连接面；
 * 进程树随 scope close 回收、stderr 转日志、有限重连（500ms→30s ×10）。
 * 本类为脚手架先行（it27 脚手架提交）；协议层与生命周期随停 1 落盘。
 */
public final class McpStdioPlugin implements Plugin {

    @Override
    public String id() {
        return "mcp-stdio";
    }

    @Override
    public void apply(Scope scope) {
        // 停 1：servers[] 解析 + stdio 客户端接线 + 进程生命周期
    }
}
