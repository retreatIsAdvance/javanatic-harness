package io.javanatic.harness.examples.headless;

import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.workbench.projection.SessionProjection;
import io.javanatic.harness.workbench.projection.SessionViews;
import io.javanatic.harness.workbench.projection.TimelineEntry;
import io.javanatic.harness.workbench.projection.TimelineEntry.TimelineDetail;
import io.javanatic.harness.workbench.projection.ToolStep;
import io.javanatic.harness.workbench.projection.UsageSummary;
import io.javanatic.harness.workbench.projection.TurnSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * it33 停 2:--session 检视的纯文本渲染面(行式,stdout 契约同 --sessions)。
 * 只渲染投影 record(不做事实解析);未知事件按 type 渲染、负载不进输出
 * (与投影层边界声明同一纪律)。五视图词表与 {@code HeadlessMain.SESSION_VIEWS}
 * 一一对应;未知视图 fail loud(parse 已拦,此处为直调兜底)。
 */
final class SessionViewRenderer {

    private SessionViewRenderer() {
    }

    /** @param loaded 落盘会话(只读消费) @param view turns|tools|usage|timeline|messages */
    static List<String> render(SessionPersistence.Loaded loaded, String view) {
        SessionViews views = SessionProjection.project(loaded);
        List<String> out = new ArrayList<>();
        out.add("session=" + views.header().id() + "  view=" + view
            + "  events=" + views.timeline().size());
        switch (view) {
            case "turns" -> turns(views, out);
            case "tools" -> tools(views, out);
            case "usage" -> usage(views, out);
            case "timeline" -> timeline(views, out);
            case "messages" -> messages(views, out);
            default -> throw new IllegalArgumentException("未知视图: " + view);
        }
        return List.copyOf(out);
    }

    private static void turns(SessionViews views, List<String> out) {
        for (TurnSummary turn : views.turns()) {
            StringBuilder line = new StringBuilder("turn=").append(turn.turn()).append("  ");
            if (turn.open()) {
                line.append("open");
            } else {
                line.append(turn.endReason())
                    .append("  duration=").append(turn.endTime() - turn.startTime()).append("ms");
            }
            line.append("  steps=").append(turn.steps())
                .append("  tools=").append(turn.toolCalls())
                .append("  ").append(turn.usage() == null ? "tokens=none" : tokens(turn.usage()));
            out.add(line.toString());
        }
    }

    private static void tools(SessionViews views, List<String> out) {
        for (ToolStep step : views.tools()) {
            for (ToolStep.Pair pair : step.pairs()) {
                StringBuilder line = new StringBuilder("turn=").append(step.turn())
                    .append(" step=").append(step.step())
                    .append("  call seq=").append(pair.callSeq())
                    .append(' ').append(pair.name())
                    .append("  args=").append(escape(pair.arguments()))
                    .append("  ->  ");
                if (pair.resultSeq() == null) {
                    line.append("无结果");
                } else {
                    line.append("seq=").append(pair.resultSeq()).append("  ")
                        .append(pair.isError() ? "error" : "ok").append("  ")
                        .append(pair.durationMillis()).append("ms");
                }
                out.add(line.toString());
            }
            for (ToolStep.Orphan orphan : step.orphans()) {
                out.add("turn=" + step.turn() + " step=" + step.step()
                    + "  orphan seq=" + orphan.resultSeq()
                    + "  " + (orphan.isError() ? "error" : "ok") + "  (无对应调用)");
            }
        }
    }

    private static void usage(SessionViews views, List<String> out) {
        out.add("total  " + (views.usage().total() == null
            ? "(无计量)" : tokens(views.usage().total())));
        for (UsageSummary.TurnUsage turn : views.usage().perTurn()) {
            out.add("turn=" + turn.turn() + "  "
                + (turn.usage() == null ? "(未报告)" : tokens(turn.usage())));
        }
    }

    private static void timeline(SessionViews views, List<String> out) {
        for (TimelineEntry entry : views.timeline()) {
            String line = entry.seq() + "  " + entry.time() + "  " + entry.type();
            if (entry.detail() != null) {
                line += "  " + detail(entry.detail());
            }
            out.add(line);
        }
    }

    private static void messages(SessionViews views, List<String> out) {
        List<Message> messages = views.messages();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            out.add("[" + (i + 1) + "]  " + sourceName(message.source())
                + "  " + blocksText(message.content()));
        }
    }

    // ────────── 渲染辅助 ──────────

    /** 已知核心事件的结构化细节渲染(封闭联合穷尽 switch;null detail 由调用方跳过)。 */
    private static String detail(TimelineDetail detail) {
        return switch (detail) {
            case TimelineDetail.Turn turn -> "turn=" + turn.turn()
                + (turn.reason() == null ? "" : " reason=" + turn.reason());
            case TimelineDetail.Step step -> "turn=" + step.turn() + " step=" + step.step();
            case TimelineDetail.User user -> "source=" + user.source() + " chars=" + user.contentChars();
            case TimelineDetail.Assistant assistant -> "turn=" + assistant.turn()
                + " step=" + assistant.step() + " chars=" + assistant.contentChars()
                + (assistant.usage() == null ? "" : " usage=" + usageText(assistant.usage()));
            case TimelineDetail.LlmRequest request -> "turn=" + request.turn()
                + " step=" + request.step() + " from=" + request.from() + " to=" + request.to()
                + " params=" + params(request.params());
            case TimelineDetail.ToolCall call -> "turn=" + call.turn() + " step=" + call.step()
                + " name=" + call.name() + " args=" + escape(call.arguments());
            case TimelineDetail.ToolResult result -> "turn=" + result.turn() + " step=" + result.step()
                + " " + (result.isError() ? "error" : "ok") + " chars=" + result.contentChars()
                + " concludes=" + result.concludesTurn();
            case TimelineDetail.CompactionStart start -> "turn=" + start.turn();
            case TimelineDetail.CompactionSummary summary -> "turn=" + summary.turn()
                + " chars=" + summary.summaryChars() + " shadowed=" + summary.shadowedStart()
                + ".." + summary.shadowedEnd()
                + (summary.usage() == null ? "" : " usage=" + usageText(summary.usage()));
            case TimelineDetail.CompactionEnd end -> "turn=" + end.turn()
                + (end.error() == null ? "" : " error=" + escape(end.error()));
            case TimelineDetail.Request request -> "cwd=" + request.cwd() + " date=" + request.date();
            case TimelineDetail.Instructions instructions -> "path=" + instructions.path()
                + " sha256=" + instructions.sha256() + " truncated=" + instructions.truncated()
                + " chars=" + instructions.contentChars();
        };
    }

    private static String tokens(TokenUsage usage) {
        return "tokens_in=" + usage.inputTokens()
            + "  tokens_out=" + usage.outputTokens()
            + "  tokens_reason=" + usage.reasoningTokens();
    }

    private static String usageText(TokenUsage usage) {
        return "in=" + usage.inputTokens()
            + " out=" + usage.outputTokens()
            + " reason=" + usage.reasoningTokens();
    }

    /** 请求参数按键排序:同输入必同输出(重放确定)。 */
    private static String params(Map<String, String> params) {
        List<String> entries = new ArrayList<>(params.size());
        new TreeMap<>(params).forEach((key, value) -> entries.add(key + "=" + value));
        return String.join(",", entries);
    }

    /** 消息来源名:核心变体按词表,扩展变体回退简单类名(与投影层同纪律)。 */
    private static String sourceName(MessageSource source) {
        return switch (source) {
            case MessageSource.User user -> "User";
            case MessageSource.Model model -> "Model";
            case MessageSource.Tool tool -> "Tool(" + tool.toolUseId() + ")";
            case MessageSource.Compaction compaction -> "Compaction";
            default -> source.getClass().getSimpleName();
        };
    }

    private static String blocksText(List<ContentBlock> blocks) {
        List<String> parts = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            parts.add(switch (block) {
                case TextBlock text -> escape(text.text());
                case ToolUseBlock use -> "<tool-use " + use.id() + " " + use.name() + "> "
                    + escape(use.arguments());
                case ToolResultBlock result -> "<tool-result " + result.toolUseId() + " "
                    + (result.isError() ? "error" : "ok") + "> " + escape(result.content());
            });
        }
        return String.join("  ", parts);
    }

    /** 行内转义:反斜杠先行,换行/回车退化为字面两字符(单行输出契约)。 */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
