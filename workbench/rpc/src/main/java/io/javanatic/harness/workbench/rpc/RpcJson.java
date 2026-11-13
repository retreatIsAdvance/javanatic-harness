package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.SessionSummary;
import io.javanatic.harness.workbench.projection.SessionViews;
import io.javanatic.harness.workbench.projection.TimelineEntry;
import io.javanatic.harness.workbench.projection.TimelineEntry.TimelineDetail;
import io.javanatic.harness.workbench.projection.ToolStep;
import io.javanatic.harness.workbench.projection.TurnSummary;
import io.javanatic.harness.workbench.projection.UsageSummary;

import java.util.List;
import java.util.Map;

/**
 * 内部 JSON 适配器(停 1-S2,设计 §5)：请求 params 手工 JsonNode 读取
 * (型错 fail loud)、结果/视图手工映射(domain record 零注解,03 §6 纪律)。
 * 映射词表钉死:discriminated union 用 kind 字段;null 恒 JSON null;
 * ToolStep.Pair.arguments / TimelineDetail.ToolCall.arguments 等原始
 * JSON 串按字符串透传(投影层契约,不二次解析)。载荷清洗:错误 message
 * 不含堆栈(wire 层兜底)。
 */
final class RpcJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RpcJson() {
    }

    // ────────── 请求面(手工读取,型错 → -32602) ──────────

    /** "max":正整数;缺省给 default;非正/非数/溢出 int → -32602。 */
    static int optionalMax(JsonNode params, int def) {
        JsonNode max = params.get("max");
        if (max == null) {
            return def;
        }
        if (!max.isIntegralNumber() || !max.canConvertToInt() || max.intValue() <= 0) {
            throw new RpcException(-32602, "invalid params");
        }
        return max.intValue();
    }

    /** "session":必填非空字符串;缺失/型错 → -32602。 */
    static String session(JsonNode params) {
        JsonNode session = params.get("session");
        if (session == null || !session.isTextual() || session.asText().isEmpty()) {
            throw new RpcException(-32602, "invalid params");
        }
        return session.asText();
    }

    /** "lastSeq":必填整数且 >= -1(-1 = 从头);缺失/型错/< -1 → -32602。 */
    static long lastSeq(JsonNode params) {
        JsonNode lastSeq = params.get("lastSeq");
        if (lastSeq == null || !lastSeq.isIntegralNumber() || !lastSeq.canConvertToLong()
                || lastSeq.asLong() < -1) {
            throw new RpcException(-32602, "invalid params");
        }
        return lastSeq.asLong();
    }

    // ────────── 通用面 ──────────

    static ObjectNode node() {
        return MAPPER.createObjectNode();
    }

    /** 域码 data:如 {"session":"<id>"}。 */
    static ObjectNode data(String key, String value) {
        ObjectNode node = node();
        node.put(key, value);
        return node;
    }

    /** 序列化字节数(响应预算判定;JsonNode 序列化不可达失败,防御性上抛)。 */
    static long sizeOf(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreachable: JsonNode serialization", e);
        }
    }

    static void setOrNull(ObjectNode node, String field, String value) {
        node.set(field, value == null ? MAPPER.nullNode() : MAPPER.getNodeFactory().textNode(value));
    }

    static void setOrNull(ObjectNode node, String field, JsonNode value) {
        node.set(field, value == null ? MAPPER.nullNode() : value);
    }

    // ────────── sessionsList ──────────

    static ObjectNode catalog(SessionPersistence.Catalog catalog) {
        ObjectNode out = node();
        ArrayNode sessions = out.putArray("sessions");
        for (SessionSummary summary : catalog.sessions()) {
            sessions.add(summary(summary));
        }
        out.put("total", catalog.total());
        return out;
    }

    private static ObjectNode summary(SessionSummary summary) {
        ObjectNode out = node();
        out.put("id", summary.id().value());
        out.put("createdAt", summary.createdAt());
        out.put("lastActivityMillis", summary.lastActivityMillis());
        out.put("eventCount", summary.eventCount());
        setOrNull(out, "cwd", summary.cwd().orElse(null));
        out.put("busy", summary.busy());
        return out;
    }

    // ────────── sessionViews ──────────

    static ObjectNode views(SessionViews views) {
        ObjectNode out = node();
        out.set("header", header(views.header()));
        ArrayNode turns = out.putArray("turns");
        for (TurnSummary turn : views.turns()) {
            turns.add(turn(turn));
        }
        ArrayNode tools = out.putArray("tools");
        for (ToolStep step : views.tools()) {
            tools.add(toolStep(step));
        }
        out.set("usage", usage(views.usage()));
        ArrayNode timeline = out.putArray("timeline");
        for (TimelineEntry entry : views.timeline()) {
            timeline.add(timelineEntry(entry));
        }
        ArrayNode messages = out.putArray("messages");
        for (Message message : views.messages()) {
            messages.add(message(message));
        }
        return out;
    }

    private static ObjectNode header(SessionHeader header) {
        ObjectNode out = node();
        out.put("version", header.version());
        out.put("id", header.id().value());
        out.put("createdAt", header.createdAt());
        setOrNull(out, "parentSession",
            header.parentSession() == null ? null : header.parentSession().value());
        out.put("seedLength", header.seedLength());
        setOrNull(out, "manifest", header.manifest() == null ? null : manifest(header.manifest()));
        return out;
    }

    /** 组合清单:config 值经 valueToTree 全量映射(组合期可信内部数据,非外部输入)。 */
    private static ObjectNode manifest(CompositionManifest manifest) {
        ObjectNode out = node();
        ArrayNode rows = out.putArray("rows");
        for (CompositionManifest.Row row : manifest.rows()) {
            ObjectNode rowNode = rows.addObject();
            rowNode.put("plugin", row.plugin());
            rowNode.set("config", MAPPER.valueToTree(row.config()));
        }
        return out;
    }

    private static ObjectNode turn(TurnSummary turn) {
        ObjectNode out = node();
        out.put("turn", turn.turn());
        out.put("open", turn.open());
        out.put("startTime", turn.startTime());
        setOrNull(out, "endTime", turn.endTime() == null ? null
            : MAPPER.getNodeFactory().numberNode(turn.endTime()));
        setOrNull(out, "endReason", turn.endReason());
        out.put("steps", turn.steps());
        out.put("toolCalls", turn.toolCalls());
        setOrNull(out, "usage", tokenUsage(turn.usage()));
        return out;
    }

    private static ObjectNode toolStep(ToolStep step) {
        ObjectNode out = node();
        out.put("turn", step.turn());
        out.put("step", step.step());
        ArrayNode pairs = out.putArray("pairs");
        for (ToolStep.Pair pair : step.pairs()) {
            ObjectNode pairNode = pairs.addObject();
            pairNode.put("callSeq", pair.callSeq());
            pairNode.put("name", pair.name());
            pairNode.put("arguments", pair.arguments());
            setOrNull(pairNode, "resultSeq", pair.resultSeq() == null ? null
                : MAPPER.getNodeFactory().numberNode(pair.resultSeq()));
            setOrNull(pairNode, "isError", pair.isError() == null ? null
                : MAPPER.getNodeFactory().booleanNode(pair.isError()));
            pairNode.put("concludesTurn", pair.concludesTurn());
            pairNode.put("durationMillis", pair.durationMillis());
        }
        ArrayNode orphans = out.putArray("orphans");
        for (ToolStep.Orphan orphan : step.orphans()) {
            ObjectNode orphanNode = orphans.addObject();
            orphanNode.put("resultSeq", orphan.resultSeq());
            orphanNode.put("isError", orphan.isError());
            orphanNode.put("concludesTurn", orphan.concludesTurn());
        }
        return out;
    }

    private static ObjectNode usage(UsageSummary usage) {
        ObjectNode out = node();
        setOrNull(out, "total", tokenUsage(usage.total()));
        ArrayNode perTurn = out.putArray("perTurn");
        for (UsageSummary.TurnUsage turn : usage.perTurn()) {
            ObjectNode turnNode = perTurn.addObject();
            turnNode.put("turn", turn.turn());
            setOrNull(turnNode, "usage", tokenUsage(turn.usage()));
        }
        return out;
    }

    private static JsonNode tokenUsage(TokenUsage usage) {
        if (usage == null) {
            return MAPPER.nullNode();
        }
        ObjectNode out = node();
        out.put("inputTokens", usage.inputTokens());
        out.put("outputTokens", usage.outputTokens());
        out.put("reasoningTokens", usage.reasoningTokens());
        return out;
    }

    // ────────── timeline(tail 与 views 共用) ──────────

    static ObjectNode timelineEntry(TimelineEntry entry) {
        ObjectNode out = node();
        out.put("seq", entry.seq());
        out.put("time", entry.time());
        out.put("type", entry.type());
        out.put("recognized", entry.recognized());
        setOrNull(out, "detail", entry.detail() == null ? null : detail(entry.detail()));
        return out;
    }

    /** 封闭联合穷尽映射(编译期检查);变体名即 kind。 */
    private static ObjectNode detail(TimelineDetail detail) {
        return switch (detail) {
            case TimelineDetail.Turn turn -> {
                ObjectNode out = node();
                out.put("kind", "turn");
                out.put("turn", turn.turn());
                setOrNull(out, "reason", turn.reason());
                yield out;
            }
            case TimelineDetail.Step step -> {
                ObjectNode out = node();
                out.put("kind", "step");
                out.put("turn", step.turn());
                out.put("step", step.step());
                yield out;
            }
            case TimelineDetail.User user -> {
                ObjectNode out = node();
                out.put("kind", "user");
                out.put("source", user.source());
                out.put("contentChars", user.contentChars());
                yield out;
            }
            case TimelineDetail.Assistant assistant -> {
                ObjectNode out = node();
                out.put("kind", "assistant");
                out.put("turn", assistant.turn());
                out.put("step", assistant.step());
                out.put("contentChars", assistant.contentChars());
                setOrNull(out, "usage", tokenUsage(assistant.usage()));
                yield out;
            }
            case TimelineDetail.LlmRequest request -> {
                ObjectNode out = node();
                out.put("kind", "llmRequest");
                out.put("turn", request.turn());
                out.put("step", request.step());
                out.put("from", request.from());
                out.put("to", request.to());
                ObjectNode params = out.putObject("params");
                for (Map.Entry<String, String> entry : request.params().entrySet()) {
                    params.put(entry.getKey(), entry.getValue());
                }
                yield out;
            }
            case TimelineDetail.ToolCall call -> {
                ObjectNode out = node();
                out.put("kind", "toolCall");
                out.put("turn", call.turn());
                out.put("step", call.step());
                out.put("name", call.name());
                out.put("arguments", call.arguments());
                yield out;
            }
            case TimelineDetail.ToolResult result -> {
                ObjectNode out = node();
                out.put("kind", "toolResult");
                out.put("turn", result.turn());
                out.put("step", result.step());
                out.put("isError", result.isError());
                out.put("contentChars", result.contentChars());
                out.put("concludesTurn", result.concludesTurn());
                yield out;
            }
            case TimelineDetail.CompactionStart start -> {
                ObjectNode out = node();
                out.put("kind", "compactionStart");
                out.put("turn", start.turn());
                yield out;
            }
            case TimelineDetail.CompactionSummary summary -> {
                ObjectNode out = node();
                out.put("kind", "compactionSummary");
                out.put("turn", summary.turn());
                out.put("summaryChars", summary.summaryChars());
                out.put("shadowedStart", summary.shadowedStart());
                out.put("shadowedEnd", summary.shadowedEnd());
                setOrNull(out, "usage", tokenUsage(summary.usage()));
                yield out;
            }
            case TimelineDetail.CompactionEnd end -> {
                ObjectNode out = node();
                out.put("kind", "compactionEnd");
                out.put("turn", end.turn());
                setOrNull(out, "error", end.error());
                yield out;
            }
            case TimelineDetail.Request request -> {
                ObjectNode out = node();
                out.put("kind", "request");
                setOrNull(out, "cwd", request.cwd());
                out.put("date", request.date());
                yield out;
            }
            case TimelineDetail.Instructions instructions -> {
                ObjectNode out = node();
                out.put("kind", "instructions");
                out.put("path", instructions.path());
                out.put("sha256", instructions.sha256());
                out.put("truncated", instructions.truncated());
                out.put("contentChars", instructions.contentChars());
                yield out;
            }
        };
    }

    // ────────── messages ──────────

    private static ObjectNode message(Message message) {
        ObjectNode out = node();
        switch (message) {
            case UserMessage user -> {
                out.put("kind", "user");
                out.set("source", source(user.source()));
                out.set("blocks", blocks(user.content()));
            }
            case AssistantMessage assistant -> {
                out.put("kind", "assistant");
                out.set("source", source(assistant.source()));
                out.set("blocks", blocks(assistant.content()));
            }
        }
        return out;
    }

    private static ObjectNode source(MessageSource source) {
        return switch (source) {
            case MessageSource.User ignored -> {
                ObjectNode out = node();
                out.put("kind", "user");
                yield out;
            }
            case MessageSource.Model model -> {
                ObjectNode out = node();
                out.put("kind", "model");
                out.put("provider", model.provider());
                out.put("model", model.model());
                yield out;
            }
            case MessageSource.Tool tool -> {
                ObjectNode out = node();
                out.put("kind", "tool");
                out.put("toolUseId", tool.toolUseId().value());
                yield out;
            }
            case MessageSource.Compaction ignored -> {
                ObjectNode out = node();
                out.put("kind", "compaction");
                yield out;
            }
            default -> throw new IllegalStateException(
                "unknown message source: " + source.getClass().getName());
        };
    }

    private static ArrayNode blocks(List<ContentBlock> content) {
        ArrayNode out = MAPPER.createArrayNode();
        for (ContentBlock block : content) {
            out.add(switch (block) {
                case TextBlock text -> {
                    ObjectNode node = node();
                    node.put("type", "text");
                    node.put("text", text.text());
                    yield node;
                }
                case ToolUseBlock use -> {
                    ObjectNode node = node();
                    node.put("type", "toolUse");
                    node.put("id", use.id().value());
                    node.put("name", use.name());
                    node.put("arguments", use.arguments());
                    yield node;
                }
                case ToolResultBlock result -> {
                    ObjectNode node = node();
                    node.put("type", "toolResult");
                    node.put("toolUseId", result.toolUseId().value());
                    node.put("content", result.content());
                    node.put("isError", result.isError());
                    yield node;
                }
            });
        }
        return out;
    }
}
