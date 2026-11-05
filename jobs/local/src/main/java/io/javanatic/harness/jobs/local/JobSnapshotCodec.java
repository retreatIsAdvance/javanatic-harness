package io.javanatic.harness.jobs.local;

import io.javanatic.harness.jobs.Job;
import io.javanatic.harness.jobs.JobFailureReason;
import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.jobs.JobState;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;
import java.time.Instant;

/**
 * job/snapshot 的持久化 codec（ServiceLoader 由持久化层发现，第七实例）。
 * 状态以 {@code variant} 判别式编码（running/succeeded/failed/cancelled/lost）；
 * 布尔以 0/1 长整型编码（JsonValue 无布尔面）；时间戳为 epoch 毫秒。
 */
public final class JobSnapshotCodec implements SessionEventCodec<JobSnapshotEvent> {

    @Override
    public String type() {
        return "job/snapshot";
    }

    @Override
    public Class<JobSnapshotEvent> typeClass() {
        return JobSnapshotEvent.class;
    }

    @Override
    public JsonValue.Obj write(JobSnapshotEvent event) {
        JobSnapshot s = event.snapshot();
        var state = JsonValue.object()
            .set("variant", variantOf(s.state()))
            .set("startedAt", startedOf(s.state()).toEpochMilli());
        if (s.state() instanceof JobState.Succeeded suc) {
            state.set("exitCode", (long) suc.exitCode()).set("finishedAt", suc.finishedAt().toEpochMilli());
        } else if (s.state() instanceof JobState.Failed failed) {
            state.set("reason", failed.reason().name()).set("exitCode", (long) failed.exitCode())
                .set("finishedAt", failed.finishedAt().toEpochMilli());
        } else if (s.state() instanceof JobState.Cancelled cancelled) {
            state.set("finishedAt", cancelled.finishedAt().toEpochMilli());
        } else if (s.state() instanceof JobState.Lost lost) {
            state.set("markedAt", lost.markedAt().toEpochMilli());
        }
        return JsonValue.object()
            .set("time", event.time())
            .set("snapshot", JsonValue.object()
                .set("id", s.id().value())
                .set("commandSummary", s.commandSummary())
                .set("state", state.build())
                .set("capturedBytes", s.capturedBytes())
                .set("outputTruncated", s.outputTruncated() ? 1L : 0L)
                .build())
            .build();
    }

    @Override
    public JobSnapshotEvent read(JsonValue.Obj body) {
        JsonValue rawSnapshot = body.get("snapshot");
        if (!(rawSnapshot instanceof JsonValue.Obj snapshot)) {
            throw new IllegalStateException("job/snapshot body has no snapshot object");
        }
        JsonValue rawState = snapshot.get("state");
        if (!(rawState instanceof JsonValue.Obj state)) {
            throw new IllegalStateException("job/snapshot snapshot has no state object");
        }
        String variant = state.get("variant").asString();
        Instant startedAt = Instant.ofEpochMilli(state.get("startedAt").asLong());
        JobState jobState = switch (variant) {
            case "running" -> new JobState.Running(startedAt);
            case "succeeded" -> new JobState.Succeeded((int) state.get("exitCode").asLong(), startedAt,
                Instant.ofEpochMilli(state.get("finishedAt").asLong()));
            case "failed" -> new JobState.Failed(JobFailureReason.valueOf(state.get("reason").asString()),
                (int) state.get("exitCode").asLong(), startedAt,
                Instant.ofEpochMilli(state.get("finishedAt").asLong()));
            case "cancelled" -> new JobState.Cancelled(startedAt,
                Instant.ofEpochMilli(state.get("finishedAt").asLong()));
            case "lost" -> new JobState.Lost(startedAt, Instant.ofEpochMilli(state.get("markedAt").asLong()));
            default -> throw new IllegalStateException("job/snapshot unknown state variant: " + variant);
        };
        JobSnapshot restored = new JobSnapshot(Job.newId(snapshot.get("id").asString()),
            snapshot.get("commandSummary").asString(), jobState,
            snapshot.get("capturedBytes").asLong(),
            snapshot.get("outputTruncated").asLong() != 0L);
        return new JobSnapshotEvent(body.get("time").asLong(), restored);
    }

    private static String variantOf(JobState state) {
        return switch (state) {
            case JobState.Running running -> "running";
            case JobState.Succeeded succeeded -> "succeeded";
            case JobState.Failed failed -> "failed";
            case JobState.Cancelled cancelled -> "cancelled";
            case JobState.Lost lost -> "lost";
        };
    }

    private static Instant startedOf(JobState state) {
        return switch (state) {
            case JobState.Running running -> running.startedAt();
            case JobState.Succeeded succeeded -> succeeded.startedAt();
            case JobState.Failed failed -> failed.startedAt();
            case JobState.Cancelled cancelled -> cancelled.startedAt();
            case JobState.Lost lost -> lost.startedAt();
        };
    }
}
