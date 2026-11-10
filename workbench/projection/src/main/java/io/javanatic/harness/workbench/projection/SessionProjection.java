package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.CompactionEnd;
import io.javanatic.harness.session.event.CompactionStart;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.ProjectInstructions;
import io.javanatic.harness.session.event.RequestHeader;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.workbench.projection.TimelineEntry.TimelineDetail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话日志的只读投影(0.5 工作台 W1 的读侧地基,W2 TUI / W3 server 共用)。
 *
 * <p><b>边界声明</b>:本层只依赖核心事件 record 与事件的 type() 字符串——
 * 未知的 ExtensionEvent 不解码、不失败,负载不进任何视图输出(timeline 只
 * 渲染 type 字符串)。seq 恒等于事件在传入列表中的下标(SessionPersistence
 * .load 已校验连续性;投影不接收 List 之外的信封信息)。
 *
 * <p>全部为纯函数:同输入必同输出;输入列表只读(调用方持有),输出为不可变
 * 快照。零写路径:不触写者锁、不追加事件、不产生副作用。
 */
public final class SessionProjection {

    private SessionProjection() {
    }

    /** 五视图聚合(Loaded → 结构化视图;CLI/TUI/server 的入口)。 */
    public static SessionViews project(SessionPersistence.Loaded loaded) {
        Objects.requireNonNull(loaded, "loaded");
        List<SessionEvent> events = loaded.events();
        SessionHeader header = loaded.header();
        return new SessionViews(header, turns(events), tools(events), usage(events),
            timeline(events), messages(events, header));
    }

    /** turns 视图:轮级摘要(轮边界、状态、时长、步/工具/计量统计)。 */
    public static List<TurnSummary> turns(List<SessionEvent> events) {
        Objects.requireNonNull(events, "events");
        Map<Integer, TurnBuilder> byTurn = new LinkedHashMap<>();
        for (SessionEvent event : events) {
            int turn = turnOf(event);
            if (turn < 0) {
                continue;
            }
            TurnBuilder builder = byTurn.get(turn);
            if (builder == null) {
                builder = new TurnBuilder(turn, event.time());
                byTurn.put(turn, builder);
            }
            builder.fold(event);
        }
        List<TurnSummary> out = new ArrayList<>(byTurn.size());
        for (TurnBuilder builder : byTurn.values()) {
            out.add(builder.build());
        }
        return List.copyOf(out);
    }

    /** tools 视图:按 (turn, step) 分组的 call/result 配对与孤儿结果。 */
    public static List<ToolStep> tools(List<SessionEvent> events) {
        Objects.requireNonNull(events, "events");
        Map<StepKey, StepBuilder> byStep = new LinkedHashMap<>();
        for (int i = 0; i < events.size(); i++) {
            SessionEvent event = events.get(i);
            if (event instanceof ToolCallEvent call) {
                byStep.computeIfAbsent(new StepKey(call.turn(), call.step()),
                    key -> new StepBuilder(key)).calls.add(new CallRec(i, call));
            } else if (event instanceof ToolResultEvent result) {
                byStep.computeIfAbsent(new StepKey(result.turn(), result.step()),
                    key -> new StepBuilder(key)).results.add(new ResultRec(i, result));
            }
        }
        List<ToolStep> out = new ArrayList<>(byStep.size());
        for (StepBuilder builder : byStep.values()) {
            out.add(builder.build());
        }
        return List.copyOf(out);
    }

    /** usage 视图:token 计量按轮 rollup + 总量。 */
    public static UsageSummary usage(List<SessionEvent> events) {
        Objects.requireNonNull(events, "events");
        Map<Integer, TokenUsage> byTurn = new LinkedHashMap<>();
        TokenUsage total = null;
        for (SessionEvent event : events) {
            if (event instanceof AssistantMessageEvent assistant) {
                // 契约:含 assistant/message 的轮必现于 perTurn;未报告计量的轮 usage=null
                byTurn.putIfAbsent(assistant.turn(), null);
                if (assistant.usage() != null) {
                    byTurn.put(assistant.turn(), add(byTurn.get(assistant.turn()), assistant.usage()));
                    total = add(total, assistant.usage());
                }
            }
        }
        List<UsageSummary.TurnUsage> perTurn = new ArrayList<>(byTurn.size());
        byTurn.forEach((turn, rollup) -> perTurn.add(new UsageSummary.TurnUsage(turn, rollup)));
        return new UsageSummary(total, List.copyOf(perTurn));
    }

    /** timeline 视图:全事件按日志序的一行一事件摘要。 */
    public static List<TimelineEntry> timeline(List<SessionEvent> events) {
        Objects.requireNonNull(events, "events");
        List<TimelineEntry> out = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            SessionEvent event = events.get(i);
            out.add(switch (event) {
                case TurnStart ts -> known(i, ts,
                    new TimelineDetail.Turn(ts.turn(), null));
                case TurnEnd te -> known(i, te,
                    new TimelineDetail.Turn(te.turn(), renderReason(te.reason())));
                case StepStart ss -> known(i, ss, new TimelineDetail.Step(ss.turn(), ss.step()));
                case StepEnd se -> known(i, se, new TimelineDetail.Step(se.turn(), se.step()));
                case UserMessageEvent um -> known(i, um,
                    new TimelineDetail.User(sourceName(um.message().source()),
                        contentChars(um.message().content())));
                case AssistantMessageEvent am -> known(i, am,
                    new TimelineDetail.Assistant(am.turn(), am.step(),
                        contentChars(am.message().content()), am.usage()));
                case LlmRequestEvent lr -> known(i, lr,
                    new TimelineDetail.LlmRequest(lr.turn(), lr.step(),
                        lr.messagesFromSeq(), lr.messagesToSeq(), lr.params()));
                case ToolCallEvent tc -> known(i, tc,
                    new TimelineDetail.ToolCall(tc.turn(), tc.step(), tc.name(), tc.arguments()));
                case ToolResultEvent tr -> known(i, tr,
                    new TimelineDetail.ToolResult(tr.turn(), tr.step(),
                        tr.block().isError(), tr.block().content().length(), tr.concludesTurn()));
                case CompactionStart cs -> known(i, cs,
                    new TimelineDetail.CompactionStart(cs.turn()));
                case CompactionSummary cs -> known(i, cs,
                    new TimelineDetail.CompactionSummary(cs.turn(), cs.summary().length(),
                        cs.shadowedStart(), cs.shadowedEnd(), cs.usage()));
                case CompactionEnd ce -> known(i, ce,
                    new TimelineDetail.CompactionEnd(ce.turn(), ce.error()));
                case RequestHeader rh -> known(i, rh,
                    new TimelineDetail.Request(rh.cwd(), rh.date()));
                case ProjectInstructions pi -> known(i, pi,
                    new TimelineDetail.Instructions(pi.path(), pi.sha256(),
                        pi.truncated(), pi.content().length()));
                case SessionEndSeedEvent seed -> known(i, seed, null);
                case ExtensionEvent ext -> unknown(i, ext);
            });
        }
        return List.copyOf(out);
    }

    /**
     * messages 视图:模型可见消息历史。复用 {@code Session.create(...).deriveMessages()}
     * 的 surface 投影(一个事实一个家,不复制投影规则);header 仅取其会话 id
     * (null 时用合成 id,不影响投影结果)。
     *
     * <p>空日志直接给空列表——{@code Session.create} 的空 seed 重放会触
     * end-seed 补记的下标越界,这里显式短路。日志 surface 元数据非法时
     * IllegalArgumentException 原样上抛(投影不校正畸形日志)。
     */
    public static List<Message> messages(List<SessionEvent> events, SessionHeader header) {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return List.of();
        }
        Id<Session> id = header != null ? header.id() : Session.newId("projection");
        return Session.create(id, events, header).deriveMessages();
    }

    // ────────── 辅助:折叠与渲染 ──────────

    /** 事件携带的轮号;无轮号事件返回 -1(不归属任何轮)。 */
    private static int turnOf(SessionEvent event) {
        return switch (event) {
            case TurnStart ts -> ts.turn();
            case TurnEnd te -> te.turn();
            case StepStart ss -> ss.turn();
            case StepEnd se -> se.turn();
            case AssistantMessageEvent am -> am.turn();
            case LlmRequestEvent lr -> lr.turn();
            case ToolCallEvent tc -> tc.turn();
            case ToolResultEvent tr -> tr.turn();
            case CompactionStart cs -> cs.turn();
            case CompactionSummary cs -> cs.turn();
            case CompactionEnd ce -> ce.turn();
            default -> -1;
        };
    }

    /**
     * TurnEndReason 渲染串:核心变体按词表(completed / aborted:&lt;cause&gt; /
     * error:&lt;KIND&gt;:&lt;message&gt;),扩展变体回退简单类名——不解析未知负载。
     */
    static String renderReason(TurnEndReason reason) {
        return switch (reason) {
            case TurnEndReason.Completed completed -> "completed";
            case TurnEndReason.Aborted aborted -> "aborted:" + aborted.cause();
            case TurnEndReason.Error error -> "error:" + error.kind().name() + ":" + error.message();
            default -> reason.getClass().getSimpleName();
        };
    }

    private static String sourceName(MessageSource source) {
        return source.getClass().getSimpleName();
    }

    private static int contentChars(List<ContentBlock> blocks) {
        int chars = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                chars += text.text().length();
            }
        }
        return chars;
    }

    private static TokenUsage add(TokenUsage left, TokenUsage right) {
        if (left == null) {
            return right;
        }
        return new TokenUsage(left.inputTokens() + right.inputTokens(),
            left.outputTokens() + right.outputTokens(),
            left.reasoningTokens() + right.reasoningTokens());
    }

    private static TimelineEntry known(long seq, SessionEvent event, TimelineDetail detail) {
        return new TimelineEntry(seq, event.time(), event.type(), true, detail);
    }

    private static TimelineEntry unknown(long seq, ExtensionEvent event) {
        return new TimelineEntry(seq, event.time(), event.type(), false, null);
    }

    // ────────── 折叠状态 ──────────

    /** turns 视图的单轮累加器。 */
    private static final class TurnBuilder {

        private final int turn;
        private boolean open = true;
        private long startTime;
        private Long endTime;
        private String endReason;
        private int steps;
        private int toolCalls;
        private TokenUsage usage;

        private TurnBuilder(int turn, long firstEventTime) {
            this.turn = turn;
            this.startTime = firstEventTime;
        }

        private void fold(SessionEvent event) {
            switch (event) {
                case TurnStart ts -> startTime = ts.time();
                case TurnEnd te -> {
                    open = false;
                    endTime = te.time();
                    endReason = renderReason(te.reason());
                }
                case StepStart ss -> steps++;
                case AssistantMessageEvent am -> {
                    if (am.usage() != null) {
                        usage = add(usage, am.usage());
                    }
                }
                case ToolCallEvent tc -> toolCalls++;
                default -> { /* 其余携带轮号的事件只参与开轮,不参与统计 */ }
            }
        }

        private TurnSummary build() {
            return new TurnSummary(turn, open, startTime, endTime, endReason,
                steps, toolCalls, usage);
        }
    }

    /** tools 视图的分组键。 */
    private record StepKey(int turn, int step) {
    }

    private record CallRec(long seq, ToolCallEvent event) {
    }

    private record ResultRec(long seq, ToolResultEvent event) {
    }

    /** tools 视图的一步累加器:callId 配对(与相邻性无关)。 */
    private static final class StepBuilder {

        private final StepKey key;
        private final List<CallRec> calls = new ArrayList<>();
        private final List<ResultRec> results = new ArrayList<>();

        private StepBuilder(StepKey key) {
            this.key = key;
        }

        private ToolStep build() {
            List<ToolStep.Pair> pairs = new ArrayList<>(calls.size());
            List<ToolStep.Orphan> orphans = new ArrayList<>(results.size());
            List<ResultRec> unmatched = new ArrayList<>(results);
            for (CallRec call : calls) {
                ResultRec match = null;
                for (ResultRec candidate : unmatched) {
                    if (candidate.event.block().toolUseId().equals(call.event.callId())) {
                        match = candidate;
                        break;
                    }
                }
                if (match != null) {
                    unmatched.remove(match);
                    pairs.add(new ToolStep.Pair(call.seq, call.event.name(), call.event.arguments(),
                        match.seq, match.event.block().isError(), match.event.concludesTurn(),
                        match.event.time() - call.event.time()));
                } else {
                    pairs.add(new ToolStep.Pair(call.seq, call.event.name(), call.event.arguments(),
                        null, null, false, -1L));
                }
            }
            for (ResultRec orphan : unmatched) {
                orphans.add(new ToolStep.Orphan(orphan.seq, orphan.event.block().isError(),
                    orphan.event.concludesTurn()));
            }
            return new ToolStep(key.turn(), key.step(), pairs, orphans);
        }
    }
}
