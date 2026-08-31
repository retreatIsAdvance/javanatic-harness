package io.javanatic.harness.session.event;

/**
 * 日志条目信封：seq 与事件的配对。seq 单调、从 0 起、等于条目在日志中的下标
 * ——只有 Session.append 会在锁内构造它，调用方无法提供、写错或并发撞号；
 * 日志外流转的都是裸事件。
 *
 * @param <T> 信封内事件的静态类型
 */
public record LoggedEvent<T extends SessionEvent>(long seq, T event) {

    /** 事件类型名直通（信封层读 type 不必拆包）。 */
    public String type() {
        return event.type();
    }
}
