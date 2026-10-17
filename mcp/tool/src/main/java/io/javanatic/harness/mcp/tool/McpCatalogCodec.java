package io.javanatic.harness.mcp.tool;

import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

import java.util.ArrayList;
import java.util.List;

/** mcp/catalog 的持久化 codec（ServiceLoader 由持久化层发现，双注册）。 */
public final class McpCatalogCodec implements SessionEventCodec<McpCatalogEvent> {

    @Override
    public String type() {
        return "mcp/catalog";
    }

    @Override
    public Class<McpCatalogEvent> typeClass() {
        return McpCatalogEvent.class;
    }

    @Override
    public JsonValue.Obj write(McpCatalogEvent event) {
        List<JsonValue> entries = new ArrayList<>(event.entries().size());
        for (McpCatalogEvent.Entry entry : event.entries()) {
            entries.add(JsonValue.object()
                .set("serverId", entry.serverId())
                .set("name", entry.name())
                .set("description", entry.description())
                .set("schema", entry.schema())
                .build());
        }
        return JsonValue.object()
            .set("time", event.time())
            .set("entries", new JsonValue.Arr(entries))
            .build();
    }

    @Override
    public McpCatalogEvent read(JsonValue.Obj body) {
        JsonValue rawEntries = body.get("entries");
        if (!(rawEntries instanceof JsonValue.Arr arr)) {
            throw new IllegalStateException("mcp/catalog body has no entries array");
        }
        List<McpCatalogEvent.Entry> entries = new ArrayList<>(arr.items().size());
        for (JsonValue item : arr.items()) {
            entries.add(new McpCatalogEvent.Entry(
                item.get("serverId").asString(),
                item.get("name").asString(),
                item.get("description").asString(),
                item.get("schema").asString()));
        }
        return new McpCatalogEvent(body.get("time").asLong(), entries);
    }
}
