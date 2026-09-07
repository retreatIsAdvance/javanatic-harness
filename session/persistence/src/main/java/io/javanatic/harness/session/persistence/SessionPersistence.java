package io.javanatic.harness.session.persistence;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.SessionEvent;

import java.io.IOException;
import java.util.List;

/**
 * 会话持久化能力(Definition)。实现订阅 session 域事件完成落盘;
 * load 供组合恢复(resume 重载经 SessionStore.create(seed))。
 */
public interface SessionPersistence {

    /** 本服务的服务键。 */
    ServiceKey<SessionPersistence> KEY = new ServiceKey<>("session-persistence");

    /** 是否耐久落盘(治理摘要与 policy 档位校验读它,07 §6;内存实现为 false)。 */
    default boolean durable() {
        return false;
    }

    /**
     * 全量重写(fork 导出用);常规增量由实现订阅 session/appended 完成。
     *
     * @param session 目标会话
     */
    void save(Session session) throws IOException;

    /**
     * 从盘读取会话(load 不建 Session——组合以 seed 交给 SessionStore)。
     *
     * @param id 会话 id
     * @return header + 全量事件(seq 连续已校验)
     * @throws java.util.NoSuchElementException 会话不存在
     * @throws IllegalStateException seq 跳号/重复或不可忽略的未知类型
     */
    Loaded load(Id<Session> id) throws IOException;

    /** load 结果。 */
    record Loaded(SessionHeader header, List<SessionEvent> events) {
    }
}
