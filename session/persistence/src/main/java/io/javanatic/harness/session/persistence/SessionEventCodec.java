package io.javanatic.harness.session.persistence;

import io.javanatic.harness.session.event.SessionEvent;

/**
 * 一种事件类型的序列化器(纯函数:事件 ↔ JsonValue 树)。核心事件 codec 由
 * jsonl 后端预置;扩展事件的 codec 由扩展插件注册(03 §6)。domain record
 * 零 Jackson 注解——持久化不反向腐蚀 Definition。
 *
 * @param <T> 事件类型
 */
public interface SessionEventCodec<T extends SessionEvent> {

    /** 与事件 {@link SessionEvent#type()} 一致(注册时校验)。 */
    String type();

    /** 对应事件类。 */
    Class<T> typeClass();

    /** @return 事件体(不含信封字段 seq/type/ignorable) */
    JsonValue.Obj write(T event);

    /** @param body 事件体(信封已剥离) @return 重建的事件 */
    T read(JsonValue.Obj body);
}
