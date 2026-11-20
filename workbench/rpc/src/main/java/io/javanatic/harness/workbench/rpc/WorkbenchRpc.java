package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.SessionTail;
import io.javanatic.harness.session.persistence.WriterLockException;
import io.javanatic.harness.workbench.projection.SessionProjection;
import io.javanatic.harness.workbench.projection.TimelineEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * workbench/rpc 方法 handler：五方法 face 的派发与 seam 消费(it36 停 1
 * 增 compositionDump 读面、停 2 增 verifyComposition 干跑校验)。构造器注入
 * {@link SessionPersistence} + {@link SessionTail} + 组合 dump 快照 +
 * {@link CompositionVerifier} + 响应预算;域错误抛
 * {@link RpcException}(wire 层原样翻译),seam 契约异常按分档翻译
 * (-32001/-32002/-32003,意外 IOException 兜底 -32603)。每请求
 * Follower 开→读→关闭环;并发请求无共享态。
 *
 * <p>写面(it38 停 1):可选注入 {@link ChatControl}(`--web-write` 开启时由装配面
 * 提供),四方法 {@code session/start|send|cancel} 与 {@code approval/respond}
 * 只做参数校验与搬运;未注入时四方法一律 -32601,行为与 0.5.0 只读面一致。
 * 域码:-32006 在途轮拒绝 send/-32007 审批请求不存在/-32008 已结算/-32009 已过期。
 *
 * <p>模块图纪律:本模块不依赖 bundle/base——dump 文本由装配面
 * (HeadlessMain)启动期算好作为 String 快照注入(组合启动后不变,
 * 热重载不做 ⇒ 快照是正确形状);verify 能力经 {@link CompositionVerifier}
 * 函数接口注入(组合级失败由装配面翻译为违规清单,rpc 面只见 List)。
 */
public final class WorkbenchRpc implements JsonRpcWire.Handler {

    /** 缺省响应预算(8 MiB,mcp T7 同族)。 */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    /**
     * 组合编辑干跑校验 seam(it36 停 2;实现归装配面——workbench/rpc 不依赖
     * bundle/base)。空列表 = 通过;非空 = 违规清单(逐项人读文本)。组合级失败
     * (YAML 解析/未知 bundle/双向校验/workspace 漂移/策略违规)一律由实现
     * 翻译为违规项;仅基础设施 IO 失败上抛(handler 翻译 -32603)。
     */
    @FunctionalInterface
    public interface CompositionVerifier {
        /**
         * @param yaml 编辑内容(完整 profile YAML;非空,参数校验在 handler)
         * @return 违规清单;空 = 通过
         * @throws IOException 基础设施失败(临时文件写失败等)
         */
        List<String> verify(String yaml) throws IOException;
    }

    static final String METHOD_SESSIONS_LIST = "workbench/sessionsList";
    static final String METHOD_SESSION_VIEWS = "workbench/sessionViews";
    static final String METHOD_SESSION_TAIL = "workbench/sessionTail";
    static final String METHOD_COMPOSITION_DUMP = "workbench/compositionDump";
    static final String METHOD_VERIFY_COMPOSITION = "workbench/verifyComposition";
    static final String METHOD_SESSION_START = "session/start";
    static final String METHOD_SESSION_SEND = "session/send";
    static final String METHOD_SESSION_CANCEL = "session/cancel";
    static final String METHOD_APPROVAL_RESPOND = "approval/respond";

    private static final int DEFAULT_SESSIONS_MAX = 20;

    private final SessionPersistence persistence;
    private final SessionTail tail;
    private final String compositionDump;
    private final CompositionVerifier verifier;
    private final long maxResponseBytes;
    private final ChatControl chat;

    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail,
                        String compositionDump, CompositionVerifier verifier) {
        this(persistence, tail, compositionDump, verifier, DEFAULT_MAX_RESPONSE_BYTES, null);
    }

    /** 写面开启：注入 {@link ChatControl}（实现归装配面）。 */
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail,
                        String compositionDump, CompositionVerifier verifier, ChatControl chat) {
        this(persistence, tail, compositionDump, verifier, DEFAULT_MAX_RESPONSE_BYTES, chat);
    }

    /** 只读面 + 自定义响应预算（0.5.0 形状，写面关闭）。 */
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail,
                        String compositionDump, CompositionVerifier verifier, long maxResponseBytes) {
        this(persistence, tail, compositionDump, verifier, maxResponseBytes, null);
    }

    /**
     * @param compositionDump 启动期组合 dump 快照(AppBoot.dump(compose(boot)) 口径)
     * @param verifier 组合编辑干跑校验 seam(装配面注入)
     * @param maxResponseBytes 序列化响应字节上限(须为正;超出 → -32004,不静默截断)
     * @param chat 写面 seam;null = 写面关闭(四方法 -32601)
     */
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail,
                        String compositionDump, CompositionVerifier verifier, long maxResponseBytes,
                        ChatControl chat) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.tail = Objects.requireNonNull(tail, "tail");
        this.compositionDump = Objects.requireNonNull(compositionDump, "compositionDump");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.chat = chat;
    }

    @Override
    public JsonNode call(String method, JsonNode params) {
        JsonNode result = switch (method) {
            case METHOD_SESSIONS_LIST -> sessionsList(params);
            case METHOD_SESSION_VIEWS -> sessionViews(params);
            case METHOD_SESSION_TAIL -> sessionTail(params);
            case METHOD_COMPOSITION_DUMP -> compositionDump();
            case METHOD_VERIFY_COMPOSITION -> verifyComposition(params);
            case METHOD_SESSION_START -> sessionStart(params);
            case METHOD_SESSION_SEND -> sessionSend(params);
            case METHOD_SESSION_CANCEL -> sessionCancel(params);
            case METHOD_APPROVAL_RESPOND -> approvalRespond(params);
            default -> throw new RpcException(-32601, "method not found");
        };
        if (RpcJson.sizeOf(result) > maxResponseBytes) {
            throw new RpcException(-32004, "response too large");
        }
        return result;
    }

    /** 无参读面:返回注入快照(params 忽略);预算约束在 call() 统一施加。 */
    private JsonNode compositionDump() {
        ObjectNode out = RpcJson.node();
        out.put("dump", compositionDump);
        return out;
    }

    /**
     * 组合编辑干跑校验(it36 停 2):yaml 必填非空 → seam 干跑 →
     * {ok:true} 或 {ok:false,violations:[...]}。零用户数据写面——
     * 干跑对象是临时 profile(装配面),不触碰已落盘 profile。
     */
    private JsonNode verifyComposition(JsonNode params) {
        String yaml = RpcJson.yaml(params);
        List<String> violations;
        try {
            violations = verifier.verify(yaml);
        } catch (IOException e) {
            throw new RpcException(-32603, "Internal error");
        }
        ObjectNode out = RpcJson.node();
        out.put("ok", violations.isEmpty());
        if (!violations.isEmpty()) {
            ArrayNode items = out.putArray("violations");
            violations.forEach(items::add);
        }
        return out;
    }

    private JsonNode sessionsList(JsonNode params) {
        int max = RpcJson.optionalMax(params, DEFAULT_SESSIONS_MAX);
        try {
            return RpcJson.catalog(persistence.list(max));
        } catch (IllegalArgumentException e) {
            throw new RpcException(-32602, "invalid params");
        } catch (IOException e) {
            throw new RpcException(-32603, "Internal error");
        }
    }

    private JsonNode sessionViews(JsonNode params) {
        String session = RpcJson.session(params);
        try {
            SessionPersistence.Loaded loaded = persistence.load(new Id<>(session));
            return RpcJson.views(SessionProjection.project(loaded));
        } catch (NoSuchElementException e) {
            throw new RpcException(-32001, "session not found", RpcJson.data("session", session));
        } catch (WriterLockException e) {
            throw new RpcException(-32002, "session locked by writer",
                RpcJson.data("session", session));
        } catch (IllegalStateException e) {
            throw new RpcException(-32003, "log corrupt", RpcJson.data("session", session));
        } catch (IOException e) {
            throw new RpcException(-32603, "Internal error");
        }
    }

    private JsonNode sessionTail(JsonNode params) {
        String session = RpcJson.session(params);
        long lastSeq = RpcJson.lastSeq(params);
        SessionTail.Follower follower;
        try {
            follower = tail.open(new Id<>(session));
        } catch (NoSuchElementException e) {
            throw new RpcException(-32001, "session not found", RpcJson.data("session", session));
        } catch (IOException e) {
            throw new RpcException(-32603, "Internal error");
        }
        try (follower) {
            List<SessionEvent> all = new ArrayList<>();
            try {
                List<SessionEvent> batch;
                while (!(batch = follower.nextEvents()).isEmpty()) {
                    all.addAll(batch);
                }
            } catch (IllegalStateException e) {
                throw new RpcException(-32003, "log corrupt", RpcJson.data("session", session));
            } catch (IOException e) {
                throw new RpcException(-32603, "Internal error");
            }
            if (lastSeq + 1 > all.size()) {
                throw new RpcException(-32602, "invalid params");
            }
            List<TimelineEntry> timeline = SessionProjection.timeline(all);
            ObjectNode out = RpcJson.node();
            ArrayNode events = out.putArray("events");
            for (long seq = lastSeq + 1; seq < all.size(); seq++) {
                events.add(RpcJson.timelineEntry(timeline.get((int) seq)));
            }
            out.put("nextSeq", all.isEmpty() ? -1L : (long) all.size() - 1);
            return out;
        }
    }

    /** 写面：开会话并跑首轮；返回新会话 id（实现归装配面）。 */
    private JsonNode sessionStart(JsonNode params) {
        ChatControl control = requireChat();
        String text = RpcJson.text(params);
        String session = control.start(text);
        ObjectNode out = RpcJson.node();
        out.put("sessionId", session);
        return out;
    }

    /** 写面：送下一轮（exit 5 答复通道）；在途轮由实现拒绝 -32006。 */
    private JsonNode sessionSend(JsonNode params) {
        ChatControl control = requireChat();
        String session = RpcJson.session(params);
        String text = RpcJson.text(params);
        control.send(session, text);
        return ok();
    }

    /** 写面：取消在途轮（aborted 落账、exit 4 语义归实现）。 */
    private JsonNode sessionCancel(JsonNode params) {
        ChatControl control = requireChat();
        String session = RpcJson.session(params);
        control.cancel(session);
        return ok();
    }

    /** 写面：审批一次性结算；迟到/重复/未知由实现按域码拒绝。 */
    private JsonNode approvalRespond(JsonNode params) {
        ChatControl control = requireChat();
        String requestId = RpcJson.requestId(params);
        boolean allow = RpcJson.allow(params);
        control.respondApproval(requestId, allow);
        return ok();
    }

    private ChatControl requireChat() {
        if (chat == null) {
            throw new RpcException(-32601, "method not found");
        }
        return chat;
    }

    private static JsonNode ok() {
        ObjectNode out = RpcJson.node();
        out.put("ok", true);
        return out;
    }
}
