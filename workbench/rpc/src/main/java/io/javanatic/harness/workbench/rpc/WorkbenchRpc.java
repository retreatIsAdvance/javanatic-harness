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
 * workbench/rpc 方法 handler：四方法 face 的派发与 seam 消费(it36 停 1
 * 增 compositionDump 读面)。构造器注入 {@link SessionPersistence} +
 * {@link SessionTail} + 组合 dump 快照 + 响应预算;域错误抛
 * {@link RpcException}(wire 层原样翻译),seam 契约异常按分档翻译
 * (-32001/-32002/-32003,意外 IOException 兜底 -32603)。每请求
 * Follower 开→读→关闭环;并发请求无共享态。
 *
 * <p>模块图纪律:本模块不依赖 bundle/base——dump 文本由装配面
 * (HeadlessMain)启动期算好作为 String 快照注入(组合启动后不变,
 * 热重载不做 ⇒ 快照是正确形状)。
 */
public final class WorkbenchRpc implements JsonRpcWire.Handler {

    /** 缺省响应预算(8 MiB,mcp T7 同族)。 */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    static final String METHOD_SESSIONS_LIST = "workbench/sessionsList";
    static final String METHOD_SESSION_VIEWS = "workbench/sessionViews";
    static final String METHOD_SESSION_TAIL = "workbench/sessionTail";
    static final String METHOD_COMPOSITION_DUMP = "workbench/compositionDump";

    private static final int DEFAULT_SESSIONS_MAX = 20;

    private final SessionPersistence persistence;
    private final SessionTail tail;
    private final String compositionDump;
    private final long maxResponseBytes;

    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail, String compositionDump) {
        this(persistence, tail, compositionDump, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * @param compositionDump 启动期组合 dump 快照(AppBoot.dump(compose(boot)) 口径)
     * @param maxResponseBytes 序列化响应字节上限(须为正;超出 → -32004,不静默截断)
     */
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail,
                        String compositionDump, long maxResponseBytes) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.tail = Objects.requireNonNull(tail, "tail");
        this.compositionDump = Objects.requireNonNull(compositionDump, "compositionDump");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public JsonNode call(String method, JsonNode params) {
        JsonNode result = switch (method) {
            case METHOD_SESSIONS_LIST -> sessionsList(params);
            case METHOD_SESSION_VIEWS -> sessionViews(params);
            case METHOD_SESSION_TAIL -> sessionTail(params);
            case METHOD_COMPOSITION_DUMP -> compositionDump();
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
}
