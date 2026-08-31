package io.javanatic.harness.session.event;

/** 开启第 {@code turn} 轮。 */
public record TurnStart(long time, int turn) implements SessionEvent {
    @Override public String type() { return "turn/start"; }
}
