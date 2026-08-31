package io.javanatic.harness.session.event;

/** 关闭第 {@code turn} 轮的第 {@code step} 步。 */
public record StepEnd(long time, int turn, int step) implements SessionEvent {
    @Override public String type() { return "step/end"; }
}
