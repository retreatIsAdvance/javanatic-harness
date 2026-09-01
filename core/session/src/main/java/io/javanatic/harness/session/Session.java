package io.javanatic.harness.session;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.SurfaceEvent;
import io.javanatic.harness.session.message.Message;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Event-sourced session：append-only 的 SessionEvent 日志 + 信封序号。
 *
 * <ul>
 *   <li>seq 在 append 锁内分配（调用方无法提供），连续性结构性成立</li>
 *   <li>事件不可变性由 record 构造时归一实现（compact 构造器 copyOf），
 *       append 不再做序列化往返冻结——可序列化是持久化 seam 的性质</li>
 *   <li>surface 校验先于变更：validateCandidate 失败则日志零变化</li>
 *   <li>观察者在落账后同步通知，逐个 contained（失败记日志不影响 append）；
 *       观察者内重入 append 拒绝（dsh 教训：交错即腐化）</li>
 *   <li>Message 历史是 derived（deriveMessages 增量缓存），不单独存储</li>
 * </ul>
 *
 * Plain class（非服务）：活跃实例经 SessionStore 创建并接线观察者。
 */
public final class Session {

    private static final Logger LOG = System.getLogger(Session.class.getName());

    /** 落账观察者（SessionStore 接线到事件总线；失败 contained）。 */
    @FunctionalInterface
    public interface Observer {
        void onAppended(Session session, LoggedEvent<? extends SessionEvent> event);
    }

    private final Id<Session> id;
    private final SessionHeader header;
    private final List<Observer> observers;
    private final List<LoggedEvent<? extends SessionEvent>> log = new ArrayList<>();
    private final SurfaceManager surface = new SurfaceManager();
    private boolean appending;
    private long firstLiveSeq;

    // 派生缓存：每节点投影一次；replace 重写后整体失效重建
    private final List<Message> derived = new ArrayList<>();
    private int derivedNodes;
    private long derivedGeneration = -1;

    Session(Id<Session> id, List<SessionEvent> seed, SessionHeader header, List<Observer> observers) {
        this.id = Objects.requireNonNull(id, "id");
        this.observers = List.copyOf(observers);
        if (seed != null) {
            for (SessionEvent event : seed) {
                accept(event, false);
            }
        }
        this.firstLiveSeq = log.size();
        this.header = header == null ? SessionHeader.fresh(id) : header;
        // seed 末尾标记：本生命周期不产生它之前的任何事件；已以其结尾则不重标（重开不增长日志）
        if (seed != null && !(log.get(log.size() - 1).event() instanceof SessionEndSeedEvent)) {
            accept(new SessionEndSeedEvent(System.currentTimeMillis()), false);
        }
    }

    /** 分离会话（无观察者）：seed 重放走与 append 相同的校验。 */
    public static Session create(Id<Session> id, List<SessionEvent> seed, SessionHeader header) {
        return new Session(id, seed, header, List.of());
    }

    /** 会话 id 工厂（品牌类型，见 kernel.brand.Id）。 */
    public static Id<Session> newId(String raw) {
        return new Id<>(raw);
    }

    /**
     * 追加事件：seq 分配、surface 校验、落账、通知，全在锁内完成。
     *
     * @throws IllegalArgumentException surface 元数据非法（replace 范围 / provenance）
     * @throws IllegalStateException 观察者内重入 append
     * @return 落账信封（seq 已分配）
     */
    public synchronized <T extends SessionEvent> LoggedEvent<T> append(T event) {
        if (appending) {
            throw new IllegalStateException("session append cannot reenter while another append is being published");
        }
        Objects.requireNonNull(event, "event");
        appending = true;
        try {
            LoggedEvent<T> entry = accept(event, true);
            return entry;
        } finally {
            appending = false;
        }
    }

    private <T extends SessionEvent> LoggedEvent<T> accept(T event, boolean notify) {
        LoggedEvent<T> entry = new LoggedEvent<>(log.size(), event);
        if (event instanceof SurfaceEvent surfaceEvent) {
            surface.validateCandidate(entry.seq(), surfaceEvent);
            surface.commit(entry);
        }
        log.add(entry);
        if (notify) {
            for (Observer observer : observers) {
                try {
                    observer.onAppended(this, entry);
                } catch (Exception e) {
                    LOG.log(Logger.Level.WARNING, "session append observer failed", e);
                }
            }
        }
        return entry;
    }

    /** 派生 LLM 消息历史（surface 节点投影，增量缓存；replace 后整体重建）。 */
    public synchronized List<Message> deriveMessages() {
        if (derivedGeneration != surface.replaceGeneration()) {
            derived.clear();
            derivedNodes = 0;
            derivedGeneration = surface.replaceGeneration();
        }
        for (int i = derivedNodes; i < surface.nodeCount(); i++) {
            LoggedEvent<? extends SessionEvent> entry = log.get((int) surface.nodeAt(i));
            Message message = DeriveMessage.project(entry.event());
            if (message != null) {
                derived.add(message);
            }
        }
        derivedNodes = surface.nodeCount();
        return List.copyOf(derived);
    }

    /** 日志快照（不可变；调用方遍历不受并发 append 影响，旧快照不增长）。 */
    public synchronized List<LoggedEvent<? extends SessionEvent>> events() {
        return List.copyOf(log);
    }

    /** 下一个 seq（恒等于日志长度——连续性契约）。 */
    public synchronized long seq() {
        return log.size();
    }

    /** 本进程首个 append 的 seq（seed 长度；其存储侧投影是 session/end-seed 事件）。 */
    public long firstLiveSeq() {
        return firstLiveSeq;
    }

    public Id<Session> id() {
        return id;
    }

    public SessionHeader header() {
        return header;
    }
}
