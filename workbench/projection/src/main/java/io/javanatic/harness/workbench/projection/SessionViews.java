package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * 五视图聚合:CLI / TUI / server 的一次投影结果(均只读)。
 * 经 {@link SessionProjection#project(io.javanatic.harness.session.persistence.SessionPersistence.Loaded)}
 * 产生;五个视图各自也可单独求值(同输入必同输出)。
 */
public record SessionViews(SessionHeader header, List<TurnSummary> turns,
                           List<ToolStep> tools, UsageSummary usage,
                           List<TimelineEntry> timeline, List<Message> messages) {

    /** @throws NullPointerException 任一组件为 null 时 */
    public SessionViews {
        Objects.requireNonNull(header, "header");
        turns = List.copyOf(turns);
        tools = List.copyOf(tools);
        Objects.requireNonNull(usage, "usage");
        timeline = List.copyOf(timeline);
        messages = List.copyOf(messages);
    }
}
