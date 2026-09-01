package io.javanatic.harness.session;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃会话存储：Session 实例的内存仓库。对应 dsh 的 ctx.sessions。
 * 持久化不在此实现：持久化插件订阅 session/appended 异步落盘，
 * flush 作为 barrier 等全部 listener 完成。
 */
public final class SessionStore {

    /** 本服务的服务键。 */
    public static final ServiceKey<SessionStore> KEY = new ServiceKey<>("session-store");

    private final ConcurrentHashMap<Id<Session>, Session> store = new ConcurrentHashMap<>();

    /**
     * 创建会话并接线：append 通知经总线以 owner 为 origin 派发 session/appended；
     * owner 关闭时移除并派发 session/disposed（R3——会话生命周期不越过创建方）。
     *
     * @param owner 创建方 scope（同时是 append 通知的 origin）
     * @param id    会话 id
     * @param options seed / header
     * @return 已接线的会话
     */
    public Session create(Scope owner, Id<Session> id, CreateOptions options) {
        Events bus = owner.require(Runtime.KEY).events();
        List<Session.Observer> observers = List.of((session, entry) ->
            bus.notify(SessionEvents.APPENDED, owner, session, entry));
        Session session = new Session(id, options.seed(), options.header(), observers);
        store.put(id, session);
        owner.onClose(() -> {
            Session removed = store.remove(id);
            if (removed != null) {
                bus.notify(SessionEvents.DISPOSED, owner, owner, removed);
            }
        });
        bus.notify(SessionEvents.CREATED, owner, owner, session);
        return session;
    }

    /** 按 id 取活跃会话。 */
    public Session get(Id<Session> id) {
        Session session = store.get(id);
        if (session == null) {
            throw new NoSuchElementException("session not in store: " + id);
        }
        return session;
    }

    /** 活跃会话快照。 */
    public List<Session> list() {
        return List.copyOf(store.values());
    }

    /** 持久化 barrier：阻塞至全部 flush listener 完成（无 listener 立即返回）。 */
    public void flush(Scope origin, Session session) {
        origin.require(Runtime.KEY).events()
            .notifyAndWait(SessionEvents.FLUSH, origin, session, session).join();
    }
}
