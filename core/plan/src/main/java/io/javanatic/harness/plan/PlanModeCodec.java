package io.javanatic.harness.plan;

import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

/** plan/mode 的持久化 codec（ServiceLoader 由持久化层发现，双注册）。 */
public final class PlanModeCodec implements SessionEventCodec<PlanModeEvent> {

    @Override public String type() { return "plan/mode"; }

    @Override public Class<PlanModeEvent> typeClass() { return PlanModeEvent.class; }

    @Override
    public JsonValue.Obj write(PlanModeEvent event) {
        return JsonValue.object()
            .set("time", event.time())
            .set("active", event.active())
            .build();
    }

    @Override
    public PlanModeEvent read(JsonValue.Obj body) {
        return new PlanModeEvent(body.get("time").asLong(), body.get("active").asBool());
    }
}
