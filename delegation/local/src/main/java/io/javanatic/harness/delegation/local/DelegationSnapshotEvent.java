package io.javanatic.harness.delegation.local;

import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.session.event.ExtensionEvent;
import java.util.Objects;

/**
 * 委派快照账（it31 停 2）：每次 delegation/snapshot = 该委派的全量快照（含
 * budgetTokens——预算随载荷入账，回放期聚合树总账），按 id fold 末值胜（无
 * generation，日志序即代）。ignorable=true：快照不进 prompt 组装（模型见到委派
 * 事实的唯一通道是工具调用 tool/result），旧读取方跳过未知不毁 R1。
 */
public record DelegationSnapshotEvent(long time, DelegationSnapshot snapshot) implements ExtensionEvent {

    @Override
    public String type() {
        return "delegation/snapshot";
    }

    @Override
    public boolean ignorable() {
        return true;
    }

    public DelegationSnapshotEvent {
        Objects.requireNonNull(snapshot, "snapshot");
        if (time < 0) {
            throw new IllegalArgumentException("time must be non-negative");
        }
    }
}
