package io.javanatic.harness.session.event;

/** 开启第 {@code turn} 轮的第 {@code step} 步（一次模型调用 + 它请求的工具执行）。 */
public record StepStart(long time, int turn, int step) implements SessionEvent {
    @Override public String type() { return "step/start"; }
}
