package io.javanatic.harness.todo;

/**
 * todo 条目状态（wire 名即持久化名，全小写下划线）。
 * 枚举收窄归工具层——ValueSchema.Str 表达不了 enum 约束。
 */
public enum TodoStatus {

    /** 未开始。 */
    PENDING("pending"),

    /** 正在进行。 */
    IN_PROGRESS("in_progress"),

    /** 已完成。 */
    COMPLETED("completed");

    private final String wire;

    TodoStatus(String wire) {
        this.wire = wire;
    }

    /** wire 名（模型参数与持久化共用）。 */
    public String wire() {
        return wire;
    }

    /**
     * @param wire 模型给的 status 串
     * @return 对应枚举
     * @throws IllegalArgumentException 未知 wire 名时（fail loud，不静默归默认）
     */
    public static TodoStatus fromWire(String wire) {
        for (TodoStatus status : values()) {
            if (status.wire.equals(wire)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown todo status: " + wire
            + " (expected pending | in_progress | completed)");
    }
}
