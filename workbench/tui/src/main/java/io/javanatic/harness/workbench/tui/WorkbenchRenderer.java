package io.javanatic.harness.workbench.tui;

import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.workbench.projection.SessionViews;
import io.javanatic.harness.workbench.projection.TimelineEntry;
import io.javanatic.harness.workbench.projection.TimelineEntry.TimelineDetail;
import io.javanatic.harness.workbench.projection.ToolStep;
import io.javanatic.harness.workbench.projection.TurnSummary;
import io.javanatic.harness.workbench.projection.UsageSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 全屏渲染器(停 2-S1):把一次投影(SessionViews)+ 视口(Viewport)+
 * 端子尺寸(Size)渲染成恰好 rows 行的屏幕文本。纯函数:同输入必同输出,
 * 无 IO、无状态。
 *
 * <p>布局:行 0 = 状态栏(session/view/events,[follow]);行 1..rows-2 =
 * 内容窗口切片(不足补空行);末行 = footer(按键提示与滚动位置)。每行
 * 截断到 cols 列(端子薄壳清行写入,不依赖行内换行)。词表与 CLI 行式
 * 渲染(HeadlessMain.SessionViewRenderer)同词汇,跨面读一致。
 */
public final class WorkbenchRenderer {

    private WorkbenchRenderer() {
    }

    /** 渲染一屏(状态栏 + 内容切片 + footer);follow 时滚动钉尾。 */
    public static Screen render(SessionViews views, Viewport viewport, Size size) {
        Objects.requireNonNull(views, "views");
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(size, "size");
        List<String> content = contentLines(views, viewport.view());
        int windowRows = windowRows(size);
        int maxTop = Math.max(0, content.size() - windowRows);
        int top = viewport.follow() ? maxTop : Math.min(viewport.scrollTop(), maxTop);
        List<String> lines = new ArrayList<>(size.rows());
        if (size.rows() >= 1) {
            lines.add(truncate(status(views, viewport), size.cols()));
        }
        for (int i = 0; i < windowRows; i++) {
            int index = top + i;
            lines.add(truncate(index < content.size() ? content.get(index) : "", size.cols()));
        }
        if (size.rows() >= 2) {
            lines.add(truncate(footer(viewport, top, windowRows, content.size()), size.cols()));
        }
        return new Screen(lines, content.size(), top);
    }

    // ────────── 行 0:状态栏 ──────────

    /** 内容窗口行数 = 总行数 - 状态栏 - footer(几何只由尺寸决定)。 */
    static int windowRows(Size size) {
        return Math.max(0, size.rows() - 2);
    }

    private static String status(SessionViews views, Viewport viewport) {
        String line = "session=" + views.header().id() + "  view=" + viewport.view().displayName()
            + "  events=" + views.timeline().size();
        return viewport.follow() ? line + "  [follow]" : line;
    }

    // ────────── 末行:footer ──────────

    private static String footer(Viewport viewport, int top, int windowRows, int contentLines) {
        if (viewport.follow()) {
            return "[跟随中] 1-5 视图 q 退出";
        }
        String line = "1-5 视图 ↑/↓ PgUp/PgDn 滚动 q 退出";
        if (windowRows > 0 && contentLines > windowRows) {
            line += "  行 " + (top + 1) + "-" + (top + windowRows) + "/" + contentLines;
        }
        return line;
    }

    // ────────── 内容窗口(当前视图的全部内容行,滚动切片由 render 负责) ──────────

    private static List<String> contentLines(SessionViews views, View view) {
        List<String> out = new ArrayList<>();
        switch (view) {
            case TURNS -> turns(views, out);
            case TOOLS -> tools(views, out);
            case USAGE -> usage(views, out);
            case TIMELINE -> timeline(views, out);
            case MESSAGES -> messages(views, out);
            default -> throw new IllegalStateException("未覆盖视图: " + view);
        }
        return out;
    }

    private static void turns(SessionViews views, List<String> out) {
        for (TurnSummary turn : views.turns()) {
            StringBuilder line = new StringBuilder("#").append(turn.turn()).append("  ");
            if (turn.open()) {
                line.append("open");
            } else {
                line.append(turn.endReason()).append("  ")
                    .append(turn.endTime() - turn.startTime()).append("ms");
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
                    .append(" call seq=").append(pair.callSeq())
                    .append(' ').append(pair.name())
                    .append(" args=").append(escape(pair.arguments()))
                    .append(" -> ");
                if (pair.resultSeq() == null) {
                    line.append("无结果");
                } else {
                    line.append("seq=").append(pair.resultSeq())
                        .append(' ').append(pair.isError() ? "error" : "ok")
                        .append(' ').append(pair.durationMillis()).append("ms");
                }
                out.add(line.toString());
            }
            for (ToolStep.Orphan orphan : step.orphans()) {
                out.add("turn=" + step.turn() + " step=" + step.step()
                    + " orphan seq=" + orphan.resultSeq()
                    + " " + (orphan.isError() ? "error" : "ok") + " (无对应调用)");
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
                + (assistant.usage() == null ? "" : " usage=" + tokens(assistant.usage()));
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
                + (summary.usage() == null ? "" : " usage=" + tokens(summary.usage()));
            case TimelineDetail.CompactionEnd end -> "turn=" + end.turn()
                + (end.error() == null ? "" : " error=" + escape(end.error()));
            case TimelineDetail.Request request -> "cwd=" + request.cwd() + " date=" + request.date();
            case TimelineDetail.Instructions instructions -> "path=" + instructions.path()
                + " sha256=" + instructions.sha256() + " truncated=" + instructions.truncated()
                + " chars=" + instructions.contentChars();
        };
    }

    private static String tokens(TokenUsage usage) {
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

    /** 消息来源名:核心变体按词表,扩展变体回退简单类名(与 CLI 渲染同纪律)。 */
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

    /** 截断到 cols 列(状态栏/内容行/footer 统一纪律)。 */
    private static String truncate(String line, int cols) {
        return line.length() <= cols ? line : line.substring(0, cols);
    }
}
