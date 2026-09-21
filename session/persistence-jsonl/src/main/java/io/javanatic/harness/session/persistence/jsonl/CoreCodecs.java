package io.javanatic.harness.session.persistence.jsonl;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.CompactionEnd;
import io.javanatic.harness.session.event.CompactionStart;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.FailureKind;
import io.javanatic.harness.session.event.RequestHeader;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionEventCodec;

import io.javanatic.harness.kernel.scope.Scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 核心 10 事件的 codec 族(预置于 jsonl 后端;扩展事件由其插件注册)。
 * 写读对称:每个事件字段名跨版本即盘上格式(v0,无兼容承诺)。
 */
final class CoreCodecs {

    private CoreCodecs() {
    }

    /** 注册全部核心 codec(挂 jsonl 插件 scope)。 */
    static void registerAll(SessionCodecRegistry registry, Scope scope) {
        List<SessionEventCodec<?>> all = List.of(
            codec("turn/start", TurnStart.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn()).build(),
                b -> new TurnStart(b.get("time").asLong(), (int) b.get("turn").asLong())),
            codec("turn/end", TurnEnd.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn())
                    .set("reason", reason(e.reason())).build(),
                b -> new TurnEnd(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    reason(b.get("reason").asObj()))),
            codec("step/start", StepStart.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn())
                    .set("step", e.step()).build(),
                b -> new StepStart(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    (int) b.get("step").asLong())),
            codec("step/end", StepEnd.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn())
                    .set("step", e.step()).build(),
                b -> new StepEnd(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    (int) b.get("step").asLong())),
            codec("user/message", UserMessageEvent.class,
                e -> {
                    JsonValue.Builder b = JsonValue.object().set("time", e.time())
                        .set("message", message(e.message()));
                    if (e.surfaceOp() instanceof SurfaceOp.Replace r) {
                        b.set("replaceStart", r.start()).set("replaceEnd", r.end());
                    }
                    if (e.sourceEventSeqs() != null) {
                        b.set("sourceEventSeqs", seqsWire(e.sourceEventSeqs()));
                    }
                    return b.build();
                },
                b -> {
                    SurfaceOp op = new SurfaceOp.Append();
                    if (b.get("replaceStart").asLong() > 0 || b.get("replaceEnd").asLong() > 0) {
                        op = new SurfaceOp.Replace(b.get("replaceStart").asLong(),
                            b.get("replaceEnd").asLong());
                    }
                    return new UserMessageEvent(b.get("time").asLong(),
                        userMessage(b.get("message").asObj()), op, sourceSeqs(b));
                }),
            codec("assistant/message", AssistantMessageEvent.class,
                e -> {
                    JsonValue.Builder b = JsonValue.object().set("time", e.time()).set("turn", e.turn())
                        .set("step", e.step()).set("message", message(e.message()))
                        .set("usage", usage(e.usage()));
                    if (e.sourceEventSeqs() != null) {
                        b.set("sourceEventSeqs", seqsWire(e.sourceEventSeqs()));
                    }
                    return b.build();
                },
                b -> new AssistantMessageEvent(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    (int) b.get("step").asLong(), assistantMessage(b.get("message").asObj()),
                    usage(b.get("usage").asObj()), new SurfaceOp.Append(), sourceSeqs(b))),
            codec("llm/request", LlmRequestEvent.class,
                e -> {
                    JsonValue.Builder params = JsonValue.object();
                    e.params().forEach(params::set);
                    return JsonValue.object().set("time", e.time()).set("turn", e.turn())
                        .set("step", e.step())
                        .set("systemPromptSha256", e.systemPromptSha256())
                        .set("toolsSchemaSha256", e.toolsSchemaSha256())
                        .set("messagesFromSeq", e.messagesFromSeq())
                        .set("messagesToSeq", e.messagesToSeq())
                        .set("params", params.build()).build();
                },
                b -> new LlmRequestEvent(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    (int) b.get("step").asLong(), b.get("systemPromptSha256").asString(),
                    b.get("toolsSchemaSha256").asString(), b.get("messagesFromSeq").asLong(),
                    b.get("messagesToSeq").asLong(), stringMap(b.get("params").asObj()))),
            codec("tool/call", ToolCallEvent.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn())
                    .set("step", e.step()).set("callId", e.callId().value())
                    .set("name", e.name()).set("arguments", e.arguments()).build(),
                b -> new ToolCallEvent(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    (int) b.get("step").asLong(), CallId.of(b.get("callId").asString()),
                    b.get("name").asString(), b.get("arguments").asString())),
            codec("tool/result", ToolResultEvent.class,
                e -> {
                    JsonValue.Builder b = JsonValue.object().set("time", e.time()).set("turn", e.turn())
                        .set("step", e.step()).set("toolUseId", e.block().toolUseId().value())
                        .set("content", e.block().content()).set("isError", e.block().isError())
                        .set("concludesTurn", e.concludesTurn());
                    if (e.sourceEventSeqs() != null) {
                        b.set("sourceEventSeqs", seqsWire(e.sourceEventSeqs()));
                    }
                    return b.build();
                },
                b -> new ToolResultEvent(b.get("time").asLong(), (int) b.get("turn").asLong(),
                    (int) b.get("step").asLong(),
                    new ToolResultBlock(CallId.of(b.get("toolUseId").asString()),
                        b.get("content").asString(), b.get("isError").asBool()),
                    b.get("concludesTurn").asBool(), new SurfaceOp.Append(), sourceSeqs(b))),
            codec("compaction/start", CompactionStart.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn()).build(),
                b -> new CompactionStart(
                    b.get("time").asLong(), (int) b.get("turn").asLong())),
            codec("compaction/summary", CompactionSummary.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn())
                    .set("summary", e.summary()).set("provider", e.provider())
                    .set("model", e.model()).set("usage", usage(e.usage()))
                    .set("shadowedStart", e.shadowedStart()).set("shadowedEnd", e.shadowedEnd()).build(),
                b -> new CompactionSummary(
                    b.get("time").asLong(), (int) b.get("turn").asLong(),
                    b.get("summary").asString(), b.get("provider").asString(),
                    b.get("model").asString(), usage(b.get("usage").asObj()),
                    b.get("shadowedStart").asLong(), b.get("shadowedEnd").asLong())),
            codec("compaction/end", CompactionEnd.class,
                e -> JsonValue.object().set("time", e.time()).set("turn", e.turn())
                    .set("error", e.error()).build(),
                b -> new CompactionEnd(
                    b.get("time").asLong(), (int) b.get("turn").asLong(),
                    b.get("error").asString())),
            codec("request/header", RequestHeader.class,
                e -> JsonValue.object().set("time", e.time()).set("cwd", e.cwd())
                    .set("date", e.date()).build(),
                b -> new RequestHeader(
                    b.get("time").asLong(), b.get("cwd").asString(), b.get("date").asString())),
            codec("session/end-seed", SessionEndSeedEvent.class,
                e -> JsonValue.object().set("time", e.time()).build(),
                b -> new SessionEndSeedEvent(b.get("time").asLong())));
        for (SessionEventCodec<?> item : all) {
            scope.onClose(registry.register(item));
        }
    }

    // ────────── 泛型适配 ──────────

    private static <T extends SessionEvent> SessionEventCodec<T> codec(
            String type, Class<T> typeClass, Writer<T> writer, Reader<T> reader) {
        return new SessionEventCodec<>() {
            @Override public String type() {
                return type;
            }

            @Override public Class<T> typeClass() {
                return typeClass;
            }

            @Override public JsonValue.Obj write(T event) {
                return writer.write(event);
            }

            @Override public T read(JsonValue.Obj body) {
                return reader.read(body);
            }
        };
    }

    @FunctionalInterface
    private interface Writer<T> {
        JsonValue.Obj write(T event);
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(JsonValue.Obj body);
    }

    // ────────── 子模型 ──────────

    /** surface 来源序的盘上形状(非 null 即写;与 Replace 判定解耦,Append 亦然)。 */
    private static JsonValue seqsWire(List<Long> seqs) {
        return new JsonValue.Arr(seqs.stream().map(seq -> (JsonValue) new JsonValue.Num(seq)).toList());
    }

    /** surface 来源序读回(键缺失 → null;v0 旧行天然兼容)。 */
    private static List<Long> sourceSeqs(JsonValue.Obj body) {
        if (body.get("sourceEventSeqs") instanceof JsonValue.Arr arr) {
            return arr.items().stream().map(JsonValue::asLong).toList();
        }
        return null;
    }

    private static JsonValue reason(TurnEndReason reason) {
        return switch (reason) {
            case TurnEndReason.Completed ignored -> JsonValue.object().set("kind", "completed").build();
            case TurnEndReason.Aborted aborted -> JsonValue.object().set("kind", "aborted")
                .set("cause", aborted.cause()).build();
            case TurnEndReason.Error error -> JsonValue.object().set("kind", "error")
                .set("message", error.message())
                .set("failureKind", wire(error.kind())).build();
            default -> throw new IllegalStateException("unknown reason: " + reason);
        };
    }

    private static TurnEndReason reason(JsonValue.Obj obj) {
        if (obj == null) {
            throw new IllegalStateException("turn/end missing reason");
        }
        return switch (obj.get("kind").asString()) {
            case "completed" -> new TurnEndReason.Completed();
            case "aborted" -> new TurnEndReason.Aborted(obj.get("cause").asString());
            // 旧日志(升级前写入)缺 failureKind → UNKNOWN:message 保留、渲染回退
            case "error" -> new TurnEndReason.Error(obj.get("message").asString(),
                failureKind(obj.get("failureKind").asString()));
            default -> throw new IllegalStateException("unknown reason kind: " + obj.get("kind"));
        };
    }

    /** 失败分类的盘上拼写(lowercase-hyphen;跨版本即格式)。 */
    private static String wire(FailureKind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** @param wire 缺失/未知拼写 → UNKNOWN(旧日志兼容与跨版本前读) */
    private static FailureKind failureKind(String wire) {
        if (wire == null) {
            return FailureKind.UNKNOWN;
        }
        try {
            return FailureKind.valueOf(wire.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return FailureKind.UNKNOWN;
        }
    }

    private static JsonValue message(Message message) {
        JsonValue.Builder builder = JsonValue.object()
            .set("source", source(message.source()));
        List<JsonValue> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            blocks.add(content(block));
        }
        return builder.set("content", new JsonValue.Arr(blocks)).build();
    }

    private static JsonValue source(MessageSource source) {
        return switch (source) {
            case MessageSource.User ignored -> JsonValue.object().set("kind", "user").build();
            case MessageSource.Model model -> JsonValue.object().set("kind", "model")
                .set("provider", model.provider()).set("model", model.model()).build();
            case MessageSource.Tool tool -> JsonValue.object().set("kind", "tool")
                .set("toolUseId", tool.toolUseId().value()).build();
            case MessageSource.Compaction ignored -> JsonValue.object().set("kind", "compaction").build();
            default -> throw new IllegalStateException("unknown source: " + source);
        };
    }

    private static JsonValue content(ContentBlock block) {
        return switch (block) {
            case TextBlock text -> JsonValue.object().set("kind", "text")
                .set("text", text.text()).build();
            case ToolUseBlock use -> JsonValue.object().set("kind", "tool-use")
                .set("id", use.id().value()).set("name", use.name())
                .set("arguments", use.arguments()).build();
            case ToolResultBlock result -> JsonValue.object().set("kind", "tool-result")
                .set("toolUseId", result.toolUseId().value())
                .set("content", result.content()).set("isError", result.isError()).build();
        };
    }

    private static UserMessage userMessage(JsonValue.Obj obj) {
        return new UserMessage(sourceOf(obj.get("source").asObj()), blocksOf(obj.get("content")));
    }

    private static AssistantMessage assistantMessage(JsonValue.Obj obj) {
        MessageSource src = sourceOf(obj.get("source").asObj());
        List<ContentBlock> blocks = blocksOf(obj.get("content"));
        return new AssistantMessage(src, blocks);
    }

    private static MessageSource sourceOf(JsonValue.Obj obj) {
        return switch (obj.get("kind").asString()) {
            case "user" -> new MessageSource.User();
            case "model" -> new MessageSource.Model(obj.get("provider").asString(),
                obj.get("model").asString());
            case "tool" -> new MessageSource.Tool(CallId.of(obj.get("toolUseId").asString()));
            case "compaction" -> new MessageSource.Compaction();
            default -> throw new IllegalStateException("unknown source kind: " + obj.get("kind"));
        };
    }

    private static List<ContentBlock> blocksOf(JsonValue arr) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (arr instanceof JsonValue.Arr array) {
            for (JsonValue item : array.items()) {
                JsonValue.Obj obj = (JsonValue.Obj) item;
                blocks.add(switch (obj.get("kind").asString()) {
                    case "text" -> new TextBlock(obj.get("text").asString());
                    case "tool-use" -> new ToolUseBlock(CallId.of(obj.get("id").asString()),
                        obj.get("name").asString(), obj.get("arguments").asString());
                    case "tool-result" -> new ToolResultBlock(
                        CallId.of(obj.get("toolUseId").asString()),
                        obj.get("content").asString(), obj.get("isError").asBool());
                    default -> throw new IllegalStateException("unknown block kind");
                });
            }
        }
        return blocks;
    }

    private static JsonValue usage(TokenUsage usage) {
        if (usage == null) {
            return JsonValue.Null.INSTANCE;
        }
        return JsonValue.object().set("input", usage.inputTokens())
            .set("output", usage.outputTokens())
            .set("reasoning", usage.reasoningTokens()).build();
    }

    private static TokenUsage usage(JsonValue.Obj obj) {
        if (obj == null) {
            return null;
        }
        return new TokenUsage(obj.get("input").asLong(), obj.get("output").asLong(),
            obj.get("reasoning").asLong());
    }

    private static Map<String, String> stringMap(JsonValue.Obj obj) {
        Map<String, String> map = new LinkedHashMap<>();
        obj.fields().forEach((key, value) -> map.put(key, value.asString()));
        return map;
    }
}
