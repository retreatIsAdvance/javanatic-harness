package io.javanatic.harness.mcp;

import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;
import java.util.Optional;

/**
 * MCP 能力契约（it27）：按 server 取连接面——工具清单快照 / 调用 / 状态。
 * 由 transport 提供者以 {@link #KEY} 提供（stdio 首版，插件 id "mcp-stdio"；
 * Streamable HTTP 挂 it27.1——同 key 双启用由 kernel fail loud，
 * shell-local/shell-docker 先例）。本接口只是只读视图：无注册面、无热变更
 * 面（清单会话启动即冻结，R1/T4）；连接与子进程生命周期归提供者 scope。
 */
public interface McpClientService {

    /** 本服务的服务键。 */
    ServiceKey<McpClientService> KEY = new ServiceKey<>("mcp");

    /**
     * 全部已注册连接，按 server id 排序（稳定面）。装配期由行配置清单
     * 一次性建立（哪怕本轮不用）；随提供者 scope 关闭回收。
     */
    List<McpServerConnection> connections();

    /**
     * 按 server id 取连接；未配置该 id 返回空（配置启停是常态事实，非错误）。
     */
    Optional<McpServerConnection> connection(String serverId);
}
