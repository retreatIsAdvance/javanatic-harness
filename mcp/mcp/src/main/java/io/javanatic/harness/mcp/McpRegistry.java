package io.javanatic.harness.mcp;

import io.javanatic.harness.kernel.scope.Disposable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link McpClientService} 注册表实现（package-private，对齐 SkillRegistry /
 * ScopedRegistry 形态）：装配期按 server id 全局唯一登记；读取每次重查、
 * 按 id 排序（稳定面）；注销幂等且只摘自己那一条（同 id 重注册不会被旧句柄误摘）。
 */
final class McpRegistry implements McpClientService {

    private final ConcurrentHashMap<String, McpServerConnection> byId = new ConcurrentHashMap<>();

    @Override
    public List<McpServerConnection> connections() {
        List<McpServerConnection> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparing(McpServerConnection::serverId));
        return List.copyOf(ordered);
    }

    @Override
    public Optional<McpServerConnection> connection(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        return Optional.ofNullable(byId.get(serverId));
    }

    @Override
    public Disposable register(McpServerConnection connection) {
        Objects.requireNonNull(connection, "connection");
        String serverId = connection.serverId();
        McpServerConnection previous = byId.putIfAbsent(serverId, connection);
        if (previous != null) {
            throw new IllegalStateException("mcp server already registered: '" + serverId + "'");
        }
        return Disposable.of(() -> byId.remove(serverId, connection));
    }
}
