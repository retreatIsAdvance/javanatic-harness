package io.javanatic.harness.sandbox.sandbox;

/**
 * 文件效果模式词表（dsh 对齐）。网络与进程可见性<b>明示在词表之外</b>——
 * 本词表只回答「这棵进程树能写哪些路径」。
 */
public enum SandboxMode {

    /** 只读：除必要汇聚点（/dev/null）外拒绝一切写。 */
    READ_ONLY("read-only"),

    /** 工作区写：只允许 workspace 根与平台临时区（见 {@link WritableRoots}）。 */
    WORKSPACE_WRITE("workspace-write"),

    /** 显式弃权：不约束（部署选择的透传档，不是隐藏默认）。 */
    DANGER_FULL_ACCESS("danger-full-access");

    private final String wire;

    SandboxMode(String wire) {
        this.wire = wire;
    }

    /** wire 名（组合配置与工具叙述共用）。 */
    public String wire() {
        return wire;
    }

    /** 是否受限档（非 DANGER——confine 只接受受限策略，透传档不进 provider）。 */
    public boolean confining() {
        return this != DANGER_FULL_ACCESS;
    }

    /**
     * @param wire 组合配置的模式名
     * @return 对应枚举
     * @throws IllegalArgumentException 未知 wire 名时（fail loud）
     */
    public static SandboxMode fromWire(String wire) {
        for (SandboxMode mode : values()) {
            if (mode.wire.equals(wire)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown sandbox mode: " + wire
            + " (expected read-only | workspace-write | danger-full-access)");
    }
}
