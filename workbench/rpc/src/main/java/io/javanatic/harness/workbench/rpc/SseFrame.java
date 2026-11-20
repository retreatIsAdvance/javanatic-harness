package io.javanatic.harness.workbench.rpc;

import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.JsonValue;

import java.util.Objects;

/** 持久化事实与临时控制消息分型；只有前者可设置 SSE id。 */
public sealed interface SseFrame {
    /** 原始日志信封，绝不经 timeline 投影。 */
    record Persisted(LoggedEvent<? extends SessionEvent> logged) implements SseFrame {
        public Persisted {
            Objects.requireNonNull(logged, "logged");
            Objects.requireNonNull(logged.event(), "event");
            if (logged.seq() < 0) {
                throw new IllegalArgumentException("persisted seq must be nonnegative");
            }
        }
    }

    /** 临时审批快照等控制消息，不占用 R1 seq。 */
    record Control(JsonValue.Obj data) implements SseFrame {
        public Control {
            Objects.requireNonNull(data, "data");
        }
    }
}
