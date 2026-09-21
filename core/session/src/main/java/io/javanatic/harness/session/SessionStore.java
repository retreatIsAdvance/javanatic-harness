package io.javanatic.harness.session;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.config.CompositionManifest;
import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃会话存储：Session 实例的内存仓库。对应 dsh 的 ctx.sessions。
 * 持久化不在此实现：持久化插件订阅 session/appended 异步落盘，
 * flush 作为 barrier 等全部 listener 完成；任一 listener 失败 ⇒
 * {@link DurabilityException}（barrier 不得静默）。
 */
public final class SessionStore {

    /** 本服务的服务键。 */
    public static final ServiceKey<SessionStore> KEY = new ServiceKey<>("session-store");

    private final ConcurrentHashMap<Id<Session>, Session> store = new ConcurrentHashMap<>();
    private final CompositionManifest manifest;

    /** @param manifest 组合清单（null = 组合未知——直装组合没有 boot 清单） */
    public SessionStore(CompositionManifest manifest) {
        this.manifest = manifest;
    }

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
            bus.notifyOrdered(SessionEvents.APPENDED, owner, session, entry));
        Session session = new Session(id, options.seed(), options.header(), manifest, observers);
        // 重复 id fail loud(it19):静默覆盖会让两个 owner 的 onClose 交叉移除
        if (store.putIfAbsent(id, session) != null) {
            throw new IllegalStateException("session already exists in store: " + id.value());
        }
        owner.onClose(() -> {
            // 值守卫:只移除本 owner 创建的实例,不误伤同 id 的后继会话
            if (store.remove(id, session)) {
                bus.notifyOrdered(SessionEvents.DISPOSED, owner, owner, session);
            }
        });
        bus.notifyOrdered(SessionEvents.CREATED, owner, owner, session);
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

    /**
     * 持久化 barrier：阻塞至全部 flush listener 完成（无 listener 立即返回）。
     * 派发前（llm/request、tool/call 批）与 dispose 前的耐久校验都走这里——
     * listener 失败（含落盘对账不符）⇒ {@link DurabilityException} fail loud。
     */
    public void flush(Scope origin, Session session) {
        try {
            origin.require(Runtime.KEY).events()
                .notifyAndWait(SessionEvents.FLUSH, origin, session, session).join();
        } catch (CompletionException e) {
            throw new DurabilityException("flush barrier failed for session "
                + session.id().value() + ": " + e.getCause(), e.getCause());
        }
    }
}
