package io.javanatic.harness.tools;

import io.javanatic.harness.llm.AbortSignal;

import java.util.Objects;

/** 一次工具执行的下文：取消信号（长操作轮询 checkAbort）。 */
public record ToolExecutionContext(AbortSignal signal) {

    /** @throws NullPointerException signal 为 null 时 */
    public ToolExecutionContext {
        Objects.requireNonNull(signal, "signal");
    }
}
