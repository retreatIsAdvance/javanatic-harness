package io.javanatic.harness.skill.tool;

import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

import java.util.ArrayList;
import java.util.List;

/** skill/catalog 的持久化 codec（ServiceLoader 由持久化层发现，双注册）。 */
public final class SkillCatalogCodec implements SessionEventCodec<SkillCatalogEvent> {

    @Override
    public String type() {
        return "skill/catalog";
    }

    @Override
    public Class<SkillCatalogEvent> typeClass() {
        return SkillCatalogEvent.class;
    }

    @Override
    public JsonValue.Obj write(SkillCatalogEvent event) {
        List<JsonValue> entries = new ArrayList<>(event.entries().size());
        for (SkillCatalogEvent.Entry entry : event.entries()) {
            entries.add(JsonValue.object()
                .set("name", entry.name())
                .set("description", entry.description())
                .set("source", entry.source())
                .build());
        }
        return JsonValue.object()
            .set("time", event.time())
            .set("entries", new JsonValue.Arr(entries))
            .build();
    }

    @Override
    public SkillCatalogEvent read(JsonValue.Obj body) {
        JsonValue rawEntries = body.get("entries");
        if (!(rawEntries instanceof JsonValue.Arr arr)) {
            throw new IllegalStateException("skill/catalog body has no entries array");
        }
        List<SkillCatalogEvent.Entry> entries = new ArrayList<>(arr.items().size());
        for (JsonValue item : arr.items()) {
            entries.add(new SkillCatalogEvent.Entry(
                item.get("name").asString(),
                item.get("description").asString(),
                item.get("source").asString()));
        }
        return new SkillCatalogEvent(body.get("time").asLong(), entries);
    }
}
