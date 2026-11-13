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
 * workbench/rpc 方法 handler(停 1-S2)：三方法 face 的派发与 seam 消费。
 * 构造器注入 {@link SessionPersistence} + {@link SessionTail} + 响应预算;
 * 域错误抛 {@link RpcException}(wire 层原样翻译),seam 契约异常按分档
 * 翻译(-32001/-32002/-32003,意外 IOException 兜底 -32603)。每请求
 * Follower 开→读→关闭环(close 今天空操作,停 2 赋义时即成真释放);
 * 并发请求无共享态。
 */
public final class WorkbenchRpc implements JsonRpcWire.Handler {

    /** 缺省响应预算(8 MiB,mcp T7 同族)。 */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    static final String METHOD_SESSIONS_LIST = "workbench/sessionsList";
    static final String METHOD_SESSION_VIEWS = "workbench/sessionViews";
    static final String METHOD_SESSION_TAIL = "workbench/sessionTail";

    private static final int DEFAULT_SESSIONS_MAX = 20;

    private final SessionPersistence persistence;
    private final SessionTail tail;
    private final long maxResponseBytes;

    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail) {
        this(persistence, tail, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * @param maxResponseBytes 序列化响应字节上限(须为正;超出 → -32004,不静默截断)
     */
    public WorkbenchRpc(SessionPersistence persistence, SessionTail tail, long maxResponseBytes) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.tail = Objects.requireNonNull(tail, "tail");
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
            default -> throw new RpcException(-32601, "method not found");
        };
        if (RpcJson.sizeOf(result) > maxResponseBytes) {
            throw new RpcException(-32004, "response too large");
        }
        return result;
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
