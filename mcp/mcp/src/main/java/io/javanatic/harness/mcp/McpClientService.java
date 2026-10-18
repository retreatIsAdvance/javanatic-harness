package io.javanatic.harness.mcp;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;
import java.util.Optional;

/**
 * MCP 能力契约：连接注册表视图（it27.1）。由 {@code mcp-registry} 插件以
 * {@link #KEY} 提供；stdio / Streamable HTTP 两个 transport 提供者都只
 * {@code register} 连接，不再各自 provide 同一 key（kernel 同 scope 重复
 * provide fail loud）。消费面只见本接口——传输形态对消费面不可见。
 *
 * <p>注册是装配期动作：server id 全局唯一（跨 transport 撞 id fail loud，
 * 不静默遮蔽）；{@link #connections()} 按 id 排序（稳定面）；连接与子进程
 * 生命周期归各提供者 scope（R3），注销经注册返回的 {@link Disposable}。
 */
public interface McpClientService {

    /** 本服务的服务键。 */
    ServiceKey<McpClientService> KEY = new ServiceKey<>("mcp");

    /**
     * 全部已注册连接，按 server id 排序（稳定面）。零 server = 空列表
     * （零连接、零网络、零进程、零事件、零工具）。
     */
    List<McpServerConnection> connections();

    /**
     * 按 server id 取连接；未配置该 id 返回空（配置启停是常态事实，非错误）。
     */
    Optional<McpServerConnection> connection(String serverId);

    /**
     * 注册一条已连接的 server（装配期；transport 提供者调用）。
     *
     * @param connection 连接面（不可为 null）
     * @return 幂等注销凭据（R3）；连接集合随调用方 scope 收口
     * @throws IllegalStateException 同一 server id 已注册（跨 transport 撞 id）
     */
    Disposable register(McpServerConnection connection);
}
