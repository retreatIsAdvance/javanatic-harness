package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.workbench.projection.SessionViews;
import io.javanatic.harness.workbench.projection.TimelineEntry;
import io.javanatic.harness.workbench.projection.TimelineEntry.TimelineDetail;
import io.javanatic.harness.workbench.projection.ToolStep;
import io.javanatic.harness.workbench.projection.TurnSummary;
import io.javanatic.harness.workbench.projection.UsageSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * S2/S3 共用的投影夹具：直接以投影 record 构造（不触 SessionEvent，单测面在
 * record 层）。views() 为八事件基准面；viewsV2() 同面多一行时间线（events=9），
 * 供 app 循环 redraw-on-change 契约使用。
 */
final class TuiFixtures {

    static final Id<Session> SID = Session.newId("tui-fixture");
    static final String STATUS_TURNS = "session=tui-fixture  view=turns  events=8";

    private TuiFixtures() {
    }

    /** 两条轮 + 两步工具 + 计量 + 八行时间线(含一行未知事件)+ 两条消息。 */
    static SessionViews views() {
        return new SessionViews(header(), turns(), tools(), usage(), timeline(8), messages());
    }

    /** 与 views() 同面但多一行时间线(events=9)：跟随态内容变化的第二快照。 */
    static SessionViews viewsV2() {
        return new SessionViews(header(), turns(), tools(), usage(), timeline(9), messages());
    }

    private static SessionHeader header() {
        return new SessionHeader(0, SID, 1_725_000_000_000L, null, 0, null);
    }

    private static List<TurnSummary> turns() {
        return List.of(
                new TurnSummary(0, false, 100L, 250L, "completed", 3, 2, new TokenUsage(1200, 800, 100)),
                new TurnSummary(1, true, 300L, null, null, 1, 0, null));
    }

    private static List<ToolStep> tools() {
        return List.of(
                new ToolStep(0, 1, List.of(new ToolStep.Pair(4, "fs_read", "{\"path\":\"/tmp\"}", 6L, false, false, 15L)),
                        List.of()),
                new ToolStep(1, 0, List.of(new ToolStep.Pair(9, "fs_write", "{}", null, null, false, -1L)),
                        List.of(new ToolStep.Orphan(11, true, false))));
    }

    private static UsageSummary usage() {
        return new UsageSummary(
                new TokenUsage(1300, 900, 100),
                List.of(
                        new UsageSummary.TurnUsage(0, new TokenUsage(1200, 800, 100)),
                        new UsageSummary.TurnUsage(1, null)));
    }

    private static List<TimelineEntry> timeline(int events) {
        List<TimelineEntry> entries = new ArrayList<>(List.of(
                new TimelineEntry(0, 100L, "request/header", true,
                        new TimelineDetail.Request("/ws", "2026-11-12T00:00:00Z")),
                new TimelineEntry(1, 110L, "turn/start", true, new TimelineDetail.Turn(0, null)),
                new TimelineEntry(2, 120L, "user/message", true, new TimelineDetail.User("User", 12)),
                new TimelineEntry(3, 130L, "tool/call", true,
                        new TimelineDetail.ToolCall(0, 1, "fs_read", "{\"path\":\"/tmp\"}")),
                new TimelineEntry(4, 140L, "tool/result", true,
                        new TimelineDetail.ToolResult(0, 1, false, 42, false)),
                new TimelineEntry(5, 150L, "assistant/message", true,
                        new TimelineDetail.Assistant(0, 1, 30, new TokenUsage(1200, 800, 100))),
                new TimelineEntry(6, 160L, "turn/end", true, new TimelineDetail.Turn(0, "completed")),
                new TimelineEntry(7, 170L, "x/custom", false, null)));
        if (events > 8) {
            entries.add(new TimelineEntry(8, 180L, "turn/end", true,
                    new TimelineDetail.Turn(1, "aborted:timeout")));
        }
        return entries;
    }

    private static List<Message> messages() {
        return List.of(
                new UserMessage(new MessageSource.User(), List.of(new TextBlock("hi"))),
                new AssistantMessage(
                        new MessageSource.Model("deepseek", "deepseek-chat"), List.of(new TextBlock("hello"))));
    }
}
