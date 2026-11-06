package io.javanatic.harness.delegation.local;

import io.javanatic.harness.delegation.Delegation;
import io.javanatic.harness.delegation.DelegationFailureReason;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;
import java.time.Instant;

/**
 * delegation/snapshot 的持久化 codec（第八实例）。六态以 {@code variant} 判别式
 * 编码（running/needs_input/succeeded/failed/cancelled/lost）；布尔无 JsonValue 面
 * 的问题不存在（本事件无布尔字段）；时间戳 epoch 毫秒；预算随载荷（R1 回放期聚合）。
 */
public final class DelegationSnapshotCodec implements SessionEventCodec<DelegationSnapshotEvent> {

    @Override
    public String type() {
        return "delegation/snapshot";
    }

    @Override
    public Class<DelegationSnapshotEvent> typeClass() {
        return DelegationSnapshotEvent.class;
    }

    @Override
    public JsonValue.Obj write(DelegationSnapshotEvent event) {
        DelegationSnapshot s = event.snapshot();
        var state = JsonValue.object()
            .set("variant", variantOf(s.state()))
            .set("startedAt", startedOf(s.state()).toEpochMilli());
        if (s.state() instanceof DelegationState.NeedsParentInput needs) {
            state.set("question", needs.question()).set("pausedAt", needs.pausedAt().toEpochMilli());
        } else if (s.state() instanceof DelegationState.Succeeded succeeded) {
            state.set("resultSummary", succeeded.resultSummary())
                .set("finishedAt", succeeded.finishedAt().toEpochMilli());
        } else if (s.state() instanceof DelegationState.Failed failed) {
            state.set("reason", failed.reason().name()).set("finishedAt", failed.finishedAt().toEpochMilli());
        } else if (s.state() instanceof DelegationState.Cancelled cancelled) {
            state.set("finishedAt", cancelled.finishedAt().toEpochMilli());
        } else if (s.state() instanceof DelegationState.Lost lost) {
            state.set("markedAt", lost.markedAt().toEpochMilli());
        }
        return JsonValue.object()
            .set("time", event.time())
            .set("snapshot", JsonValue.object()
                .set("id", s.id().value())
                .set("taskSummary", s.taskSummary())
                .set("childSessionId", s.childSessionId().value())
                .set("budgetTokens", s.budgetTokens())
                .set("state", state.build())
                .build())
            .build();
    }

    @Override
    public DelegationSnapshotEvent read(JsonValue.Obj body) {
        JsonValue rawSnapshot = body.get("snapshot");
        if (!(rawSnapshot instanceof JsonValue.Obj snapshot)) {
            throw new IllegalStateException("delegation/snapshot body has no snapshot object");
        }
        JsonValue rawState = snapshot.get("state");
        if (!(rawState instanceof JsonValue.Obj state)) {
            throw new IllegalStateException("delegation/snapshot snapshot has no state object");
        }
        String variant = state.get("variant").asString();
        Instant startedAt = Instant.ofEpochMilli(state.get("startedAt").asLong());
        DelegationState restored = switch (variant) {
            case "running" -> new DelegationState.Running(startedAt);
            case "needs_input" -> new DelegationState.NeedsParentInput(state.get("question").asString(),
                startedAt, Instant.ofEpochMilli(state.get("pausedAt").asLong()));
            case "succeeded" -> new DelegationState.Succeeded(state.get("resultSummary").asString(),
                startedAt, Instant.ofEpochMilli(state.get("finishedAt").asLong()));
            case "failed" -> new DelegationState.Failed(
                DelegationFailureReason.valueOf(state.get("reason").asString()), startedAt,
                Instant.ofEpochMilli(state.get("finishedAt").asLong()));
            case "cancelled" -> new DelegationState.Cancelled(startedAt,
                Instant.ofEpochMilli(state.get("finishedAt").asLong()));
            case "lost" -> new DelegationState.Lost(startedAt,
                Instant.ofEpochMilli(state.get("markedAt").asLong()));
            default -> throw new IllegalStateException("delegation/snapshot unknown variant: " + variant);
        };
        DelegationSnapshot snap = new DelegationSnapshot(Delegation.newId(snapshot.get("id").asString()),
            snapshot.get("taskSummary").asString(), Session.newId(snapshot.get("childSessionId").asString()),
            snapshot.get("budgetTokens").asLong(), restored);
        return new DelegationSnapshotEvent(body.get("time").asLong(), snap);
    }

    private static String variantOf(DelegationState state) {
        return switch (state) {
            case DelegationState.Running running -> "running";
            case DelegationState.NeedsParentInput needsParentInput -> "needs_input";
            case DelegationState.Succeeded succeeded -> "succeeded";
            case DelegationState.Failed failed -> "failed";
            case DelegationState.Cancelled cancelled -> "cancelled";
            case DelegationState.Lost lost -> "lost";
        };
    }

    private static Instant startedOf(DelegationState state) {
        return switch (state) {
            case DelegationState.Running running -> running.startedAt();
            case DelegationState.NeedsParentInput needsParentInput -> needsParentInput.startedAt();
            case DelegationState.Succeeded succeeded -> succeeded.startedAt();
            case DelegationState.Failed failed -> failed.startedAt();
            case DelegationState.Cancelled cancelled -> cancelled.startedAt();
            case DelegationState.Lost lost -> lost.startedAt();
        };
    }
}
