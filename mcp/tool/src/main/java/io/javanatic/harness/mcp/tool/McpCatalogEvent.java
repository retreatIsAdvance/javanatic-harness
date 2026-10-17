package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.session.event.ExtensionEvent;

import java.util.List;
import java.util.Objects;

/**
 * MCP 工具清单账（log-only 扩展事件，it27 内容 4）：快照 = 会话启动时已注册
 * （schema 映射成功）工具的逐条 (server id / raw name / description / 参数
 * schema 原文)。首个 REQUEST 落账；此后冻结清单一致零噪声、不一致 fail loud
 * （请重开会话）。ignorable=false：清单是注册面与请求指纹的事实源，回放重建
 * 工具面（fold）零联网零进程——静默丢弃会毁 R1（skill/catalog 同款理据）。
 */
public record McpCatalogEvent(long time, List<Entry> entries) implements ExtensionEvent {

    @Override
    public String type() {
        return "mcp/catalog";
    }

    @Override
    public boolean ignorable() {
        return false;
    }

    public McpCatalogEvent {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /** 清单条目：schema 为 wire 原文（fold 侧据此做确定性映射重建注册面）。 */
    public record Entry(String serverId, String name, String description, String schema) {

        /** @throws NullPointerException 任一字段为 null 时 */
        public Entry {
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(schema, "schema");
        }
    }
}
