package io.javanatic.harness.mcp.stdio;

import io.javanatic.harness.mcp.McpClientService;
import io.javanatic.harness.mcp.McpServerConnection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link McpClientService} 的 stdio 实现：装配期建立的一次性不可变视图
 * （无注册面、无热变更面）。连接按 server id 排序暴露（稳定面——
 * 暴露序不随行配置序或 Map 迭代序漂移）。
 */
final class StdioClientService implements McpClientService {

    private final List<McpServerConnection> ordered;
    private final Map<String, McpServerConnection> byId;

    StdioClientService(List<StdioMcpConnection> connections) {
        List<StdioMcpConnection> sorted = new ArrayList<>(connections);
        sorted.sort(Comparator.comparing(StdioMcpConnection::serverId));
        this.ordered = List.copyOf(sorted);
        Map<String, McpServerConnection> index = new LinkedHashMap<>();
        for (StdioMcpConnection connection : sorted) {
            index.put(connection.serverId(), connection);
        }
        this.byId = Map.copyOf(index);
    }

    @Override
    public List<McpServerConnection> connections() {
        return ordered;
    }

    @Override
    public Optional<McpServerConnection> connection(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        return Optional.ofNullable(byId.get(serverId));
    }
}
