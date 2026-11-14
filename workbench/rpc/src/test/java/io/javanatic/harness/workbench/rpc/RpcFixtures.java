package io.javanatic.harness.workbench.rpc;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.SessionSummary;
import io.javanatic.harness.session.persistence.SessionTail;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 停 1-S2 handler 测试夹具:内存 seam 替身(真实 jsonl 后端的契约已由
 * persistence-jsonl 自身测试钉死;rpc 模块只 depends API seam——模块图纪律)
 * + 核心事件紧凑工厂(TestEvents 同款形状)。替身异常按真实类型上抛
 * (NoSuchElementException / WriterLockException / IllegalStateException),
 * handler 只依赖这些类型的翻译契约。
 */
final class RpcFixtures {

    static final long USAGE_IN = 10L;
    static final long USAGE_OUT = 20L;

    private RpcFixtures() {
    }

    // ────────── 事件工厂(TestEvents 同款,全部 Append 面、显式时间) ──────────

    static TurnStart turnStart(long time, int turn) {
        return new TurnStart(time, turn);
    }

    static TurnEnd turnEnd(long time, int turn, TurnEndReason reason) {
        return new TurnEnd(time, turn, reason);
    }

    static StepStart stepStart(long time, int turn, int step) {
        return new StepStart(time, turn, step);
    }

    static ToolCallEvent toolCall(long time, int turn, int step, String callId,
                                  String name, String arguments) {
        return new ToolCallEvent(time, turn, step, CallId.of(callId), name, arguments);
    }

    static ToolResultEvent toolResult(long time, int turn, int step, String callId,
                                      String content, boolean isError) {
        return new ToolResultEvent(time, turn, step,
            new ToolResultBlock(CallId.of(callId), content, isError), false,
            new SurfaceOp.Append(), null);
    }

    /** 携带工具结果块的用户消息(source=Tool):deriveMessages 的 tools 切片形态。 */
    static UserMessageEvent userToolResult(long time, String callId, String content) {
        return new UserMessageEvent(time,
            new UserMessage(new MessageSource.Tool(CallId.of(callId)),
                List.of(new ToolResultBlock(CallId.of(callId), content, false))),
            new SurfaceOp.Append(), null);
    }

    /** 携带工具调用块的助手消息。 */
    static AssistantMessageEvent assistantToolUse(long time, int turn, int step,
                                                  String callId, TokenUsage usage) {
        return new AssistantMessageEvent(time, turn, step,
            new AssistantMessage(new MessageSource.Model("p", "m"),
                List.of(new ToolUseBlock(CallId.of(callId), "fs/read", "{}"))),
            usage, new SurfaceOp.Append(), null);
    }

    static LlmRequestEvent llmRequest(long time, int turn, int step, long from, long to) {
        return new LlmRequestEvent(time, turn, step, "sha-system", "sha-tools", from, to,
            Map.of("model", "m"));
    }

    static SessionEndSeedEvent endSeed(long time) {
        return new SessionEndSeedEvent(time);
    }

    /** 未知扩展事件:payload 的 toString 即炸——映射只读 type(),触碰负载当场失败。 */
    record FakeExtension(long time, String kind, Object payload) implements ExtensionEvent {

        @Override
        public String type() {
            return "fake/" + kind;
        }

        @Override
        public boolean ignorable() {
            return true;
        }
    }

    // ────────── 内存 seam 替身 ──────────

    /** 可配置失败的内存 SessionPersistence:异常按真实类型上抛。 */
    static final class FakePersistence implements SessionPersistence {

        final Map<String, SessionPersistence.Loaded> sessions = new HashMap<>();
        List<SessionSummary> catalog = List.of();
        int total;
        int lastMax = -1;
        RuntimeException runtimeFailure;
        IOException ioFailure;

        @Override
        public void save(Session session) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public SessionPersistence.Loaded load(Id<Session> id) throws IOException {
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            SessionPersistence.Loaded loaded = sessions.get(id.value());
            if (loaded == null) {
                throw new NoSuchElementException("session not on disk: " + id.value());
            }
            return loaded;
        }

        @Override
        public SessionPersistence.Catalog list(int max) {
            lastMax = max;
            return new SessionPersistence.Catalog(catalog, total);
        }
    }

    /** 可配置失败的内存 SessionTail:分批交付(缺省一批全量)、之后空批;撕裂尾由
     *  seam 不交付,handler 只见完整批。batchSize 钉多批排空路径。close 镜像
     *  seam 契约(停 2 赋义):Follower 关后拒读 fail loud,外层 closed 记透传。 */
    static final class FakeTail implements SessionTail {

        final Map<String, List<SessionEvent>> sessions = new HashMap<>();
        int batchSize = Integer.MAX_VALUE;
        RuntimeException openFailure;
        boolean closed;

        @Override
        public Follower open(Id<Session> id) {
            if (openFailure != null) {
                throw openFailure;
            }
            List<SessionEvent> events = sessions.get(id.value());
            if (events == null) {
                throw new NoSuchElementException("session not on disk: " + id.value());
            }
            return new Follower() {

                private int cursor;
                /** close 赋义镜像(停 2 契约):关后 header/nextEvents 拒读 fail loud。 */
                private boolean terminated;

                @Override
                public SessionHeader header() {
                    if (terminated) {
                        throw new IllegalStateException("follower closed");
                    }
                    return headerOf(events);
                }

                @Override
                public List<SessionEvent> nextEvents() {
                    if (terminated) {
                        throw new IllegalStateException("follower closed");
                    }
                    if (cursor >= events.size()) {
                        return List.of();
                    }
                    int from = cursor;
                    cursor = Math.min(cursor + batchSize, events.size());
                    return events.subList(from, cursor);
                }

                @Override
                public void close() {
                    terminated = true;
                    closed = true; // FakeTail 级透传标志(server close 断言面)
                }
            };
        }
    }

    /** 三事件基准会话(seq 0..2):turn/start、turn/end、session/end-seed。 */
    static List<SessionEvent> tailEvents() {
        return List.of(
            turnStart(100, 1),
            turnEnd(200, 1, new TurnEndReason.Completed()),
            endSeed(300));
    }

    /** 摘要夹具:两条(cwd 有/无、busy 真/假)+ total=5(截断标记)。 */
    static SessionSummary summary(String id, long activity, Optional<String> cwd, boolean busy) {
        return new SessionSummary(new Id<>(id), 1L, activity, 3L, cwd, busy);
    }

    /** 替身 header:跟随者的 header() 面(handler 不消费,但接口形状要求)。 */
    private static SessionHeader headerOf(List<SessionEvent> events) {
        return new SessionHeader(SessionHeader.FORMAT_VERSION, new Id<>("s1"), 0L, null, 0, null);
    }

    // ────────── 富会话(handler 与绑定面 E2E 共用) ──────────

    /** Loaded 拼装:带 manifest 的 header + 事件表。 */
    static SessionPersistence.Loaded loaded(List<SessionEvent> events) {
        CompositionManifest manifest = new CompositionManifest(
            List.of(new CompositionManifest.Row("p", Map.of("k", "v"))));
        SessionHeader header = new SessionHeader(SessionHeader.FORMAT_VERSION,
            new Id<Session>("s1"), 0L, null, 0, manifest);
        return new SessionPersistence.Loaded(header, events);
    }

    /** 富会话:九事件(消息窗口 4..5 含工具调用/结果块)。
     *  seq3 原始 tool/result 与 seq4 携带同形结果块的 user 消息各派生一条 user 消息
     *  (core-session 每节点派生规则,DeriveMessage.project)——预期 messages 两条 user
     *  即此派生结果,非映射重复。 */
    static List<SessionEvent> richEvents() {
        TokenUsage usage = new TokenUsage(10, 20, 0);
        return List.of(
            turnStart(100, 1),
            stepStart(110, 1, 1),
            toolCall(120, 1, 1, "c1", "fs/read", "{\"path\":\"x\"}"),
            toolResult(130, 1, 1, "c1", "content", false),
            userToolResult(140, "c1", "content"),
            assistantToolUse(150, 1, 1, "c1", usage),
            llmRequest(160, 1, 1, 4, 5),
            turnEnd(170, 1, new TurnEndReason.Completed()),
            endSeed(180));
    }
}
