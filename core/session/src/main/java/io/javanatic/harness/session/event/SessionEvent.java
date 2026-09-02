package io.javanatic.harness.session.event;

/**
 * Session 日志的一个不可变事件。
 *
 * <p>sealed：核心事件编译期穷尽（switch 漏分支即编译错误）；{@link ExtensionEvent}
 * 是 non-sealed 扩展出口，switch 用显式分支处理。对应 dsh 的 merge-extensible
 * SessionEventMap——封闭联合穷尽、可扩展联合走文档化默认分支。
 *
 * <p>seq 不在此（在 {@link LoggedEvent} 信封上，由 Session.append 锁内分配）；
 * time 在此——R1 规定提示词组装只读日志不读环境时钟，事件自带时间是该规则的载体。
 */
public sealed interface SessionEvent permits
    TurnStart, TurnEnd, StepStart, StepEnd,
    UserMessageEvent, AssistantMessageEvent, LlmRequestEvent,
    ToolCallEvent, ToolResultEvent,
    SessionEndSeedEvent, ExtensionEvent {

    /** Unix epoch 毫秒。 */
    long time();

    /** 事件类型名（持久化 key，跨实现兼容，斜杠小写如 "turn/start"）。 */
    String type();

    /**
     * 读取方可安全跳过的未知事件标记。默认 false（required）：未知事件拒绝重建，
     * 而非静默丢弃——忘记标记只会过度拒绝，不会静默吞掉改变重建语义的事件。
     * 序列化时写在信封行，读取方无需解码事件体即可决定跳过。
     */
    default boolean ignorable() { return false; }
}
