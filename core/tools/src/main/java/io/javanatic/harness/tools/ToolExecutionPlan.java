package io.javanatic.harness.tools;

import io.javanatic.harness.session.message.ToolUseBlock;

import java.util.Objects;

/**
 * pre-execute 的裁决：放行（携带调用，vetoReason 为 null）或否决
 * （vetoReason 非空，call 为 null）。规范构造器无法收窄可见性（JLS），
 * 不变量由工厂保证：外部请用 {@link #proceed}/{@link #veto}。
 */
public record ToolExecutionPlan(ToolUseBlock call, String vetoReason) {

    /** @param call 放行的调用 */
    public static ToolExecutionPlan proceed(ToolUseBlock call) {
        return new ToolExecutionPlan(Objects.requireNonNull(call, "call"), null);
    }

    /** @param reason 否决原因（进模型可见的 error result） */
    public static ToolExecutionPlan veto(String reason) {
        return new ToolExecutionPlan(null, Objects.requireNonNull(reason, "reason"));
    }

    /** 是否否决。 */
    public boolean vetoed() {
        return vetoReason != null;
    }
}
