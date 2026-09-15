package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

/** command/run 的持久化 codec（ServiceLoader 由持久化层发现，双注册）。 */
public final class CommandRunCodec implements SessionEventCodec<CommandRunEvent> {

    @Override public String type() { return "command/run"; }

    @Override public Class<CommandRunEvent> typeClass() { return CommandRunEvent.class; }

    @Override
    public JsonValue.Obj write(CommandRunEvent event) {
        return JsonValue.object()
            .set("time", event.time())
            .set("name", event.name())
            .set("rawInput", event.rawInput())
            .build();
    }

    @Override
    public CommandRunEvent read(JsonValue.Obj body) {
        return new CommandRunEvent(body.get("time").asLong(),
            body.get("name").asString(), body.get("rawInput").asString());
    }
}
