package io.javanatic.harness.sandbox.sandbox;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次受限执行的完整文件效果策略——**逐调用携带，不固定在 provider 上**：
 * 两个消费者可同刻以不同策略约束（bash 只读而并行会话工作区写）。
 * 默认值解析是消费端的一次显式 resolve（08 §7），provider 视策略为全指定。
 *
 * @param mode          文件效果模式（透传档也携带——调用方一次解析后自选路径）
 * @param workspaceRoot workspace-write 允许写入的绝对根（各模式恒携带）
 */
public record SandboxPolicy(SandboxMode mode, Path workspaceRoot) {

    /** @throws NullPointerException 任一字段为 null、workspaceRoot 非绝对路径时 */
    public SandboxPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (!workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("workspaceRoot must be absolute: " + workspaceRoot);
        }
    }

    /** 是否受限档——受限才进 {@link SandboxProvider#confine}，透传档直接 spawn。 */
    public boolean confining() {
        return mode.confining();
    }
}
