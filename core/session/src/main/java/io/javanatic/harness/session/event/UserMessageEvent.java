package io.javanatic.harness.session.event;

import io.javanatic.harness.session.message.UserMessage;

import java.util.List;
import java.util.Objects;

/** surface 上的用户消息（人类输入 / 注入上下文，由 {@code MessageSource.User} 区分）。 */
public record UserMessageEvent(
    long time,
    UserMessage message,
    SurfaceOp surfaceOp,
    List<Long> sourceEventSeqs
) implements SessionEvent, SurfaceEvent {

    /** @throws NullPointerException message/surfaceOp 为 null 时 */
    public UserMessageEvent {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(surfaceOp, "surfaceOp");
        sourceEventSeqs = sourceEventSeqs == null ? null : List.copyOf(sourceEventSeqs);
    }

    @Override public String type() { return "user/message"; }
}
