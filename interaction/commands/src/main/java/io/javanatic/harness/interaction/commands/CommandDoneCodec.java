package io.javanatic.harness.interaction.commands;

import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

/** command/done 的持久化 codec（ServiceLoader 由持久化层发现，双注册）。 */
public final class CommandDoneCodec implements SessionEventCodec<CommandDoneEvent> {

    @Override public String type() { return "command/done"; }

    @Override public Class<CommandDoneEvent> typeClass() { return CommandDoneEvent.class; }

    @Override
    public JsonValue.Obj write(CommandDoneEvent event) {
        return JsonValue.object()
            .set("time", event.time())
            .set("name", event.name())
            .set("ok", event.ok())
            .set("detail", event.detail())
            .build();
    }

    @Override
    public CommandDoneEvent read(JsonValue.Obj body) {
        return new CommandDoneEvent(body.get("time").asLong(),
            body.get("name").asString(), body.get("ok").asBool(), body.get("detail").asString());
    }
}
