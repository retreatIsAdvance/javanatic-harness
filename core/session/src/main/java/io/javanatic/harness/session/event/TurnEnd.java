package io.javanatic.harness.session.event;

import java.util.Objects;

/** 关闭第 {@code turn} 轮，携带结束原因。 */
public record TurnEnd(long time, int turn, TurnEndReason reason) implements SessionEvent {

    /** @throws NullPointerException time/reason 为 null 时 */
    public TurnEnd {
        Objects.requireNonNull(reason, "reason");
    }

    @Override public String type() { return "turn/end"; }
}
