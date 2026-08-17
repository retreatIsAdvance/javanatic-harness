package io.javanatic.harness.kernel.events;

import java.util.Objects;

/**
 * 事件名 + 类型 token + 模式。只有两种模式；serial / parallel / bail 形态由
 * {@link Events} 的工具方法表达（docs/design/01-kernel.md §5）。
 *
 * @param <T> 事件负载类型
 * @param name 事件名；全局唯一
 * @param type 负载类型 token（订阅/派发类型对齐的载体）
 * @param mode 派发模式
 */
public record EventKey<T>(String name, Class<T> type, Mode mode) {

    /** 派发模式：NOTIFY（通知族）或 WATERFALL（中间件/查询族）。 */
    public enum Mode { NOTIFY, WATERFALL }

    public EventKey {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("EventKey name must be non-empty");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
    }

    /** 创建 NOTIFY key。 */
    public static <T> EventKey<T> notify(String name, Class<T> type) {
        return new EventKey<>(name, type, Mode.NOTIFY);
    }

    /** 创建 WATERFALL key。 */
    public static <T> EventKey<T> waterfall(String name, Class<T> type) {
        return new EventKey<>(name, type, Mode.WATERFALL);
    }

    /** 模式契约校验：订阅与派发两侧都与 key 的 mode 配对，不符即 fail loud。 */
    void requireMode(Mode expected) {
        if (mode != expected) {
            throw new IllegalStateException("Event " + name + " is " + mode + ", expected " + expected);
        }
    }
}
