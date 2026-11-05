package io.javanatic.harness.jobs.local;

import io.javanatic.harness.jobs.JobSnapshot;
import io.javanatic.harness.session.event.ExtensionEvent;
import java.util.Objects;

/**
 * 任务快照账（it30 停 2）：每次 job/snapshot 事件 = 该任务的全量快照，
 * 消费方按 id fold 末值胜（无 generation 字段，日志序即代，B16 同精神）。
 * ignorable=true：快照不进 prompt 组装（模型见到 job 事实的唯一通道是
 * 工具调用 tool/result），旧读取方跳过未知不毁 R1。
 */
public record JobSnapshotEvent(long time, JobSnapshot snapshot) implements ExtensionEvent {

    @Override
    public String type() {
        return "job/snapshot";
    }

    @Override
    public boolean ignorable() {
        return true;
    }

    public JobSnapshotEvent {
        Objects.requireNonNull(snapshot, "snapshot");
        if (time < 0) {
            throw new IllegalArgumentException("time must be non-negative");
        }
    }
}
