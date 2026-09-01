package io.javanatic.harness.session;

import io.javanatic.harness.kernel.events.EventKey;
import io.javanatic.harness.session.event.LoggedEvent;

/** session 域的事件键（Definition 持有，Provider/Consumer 引用）。 */
public final class SessionEvents {

    /** 会话创建（carrier = 创建方 scope，payload = 会话）。 */
    public static final EventKey<Session> CREATED = EventKey.notify("session/created", Session.class);

    /** 会话从 store 移除（关闭回收路径）。 */
    public static final EventKey<Session> DISPOSED = EventKey.notify("session/disposed", Session.class);

    /** 持久化 barrier：notifyAndWait，全部 flush listener 完成才返回。 */
    public static final EventKey<Session> FLUSH = EventKey.notify("session/flush", Session.class);

    /**
     * 逐条落账通知（carrier = 所属会话，payload = 信封）。payload 泛型经原始类型
     * 降级（Class 字面量无法表达通配参数化）——listener 收到的 {@code event()}
     * 静态类型即 {@code SessionEvent}，见 08 §2 的 Map→Derived Union 说明。
     */
    @SuppressWarnings("rawtypes")
    public static final EventKey<LoggedEvent> APPENDED =
        EventKey.notify("session/appended", LoggedEvent.class);

    private SessionEvents() {
    }
}
