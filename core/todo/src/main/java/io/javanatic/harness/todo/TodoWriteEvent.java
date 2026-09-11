package io.javanatic.harness.todo;

import io.javanatic.harness.session.event.ExtensionEvent;

import java.util.List;
import java.util.Objects;

/**
 * todo 整表快照（log-only 扩展事件）：每次 todo_write 落一条<b>全量替换表</b>，
 * 回放末值胜。不参与模型可见投影——模型经 tool/result 叙述与后续请求感知。
 * ignorable=false：静默丢弃会丢失已落账状态，未知读取方应拒绝重建。
 */
public record TodoWriteEvent(long time, List<TodoItem> todos) implements ExtensionEvent {

    /** @throws NullPointerException todos 或其元素为 null 时 */
    public TodoWriteEvent {
        Objects.requireNonNull(todos, "todos");
        todos = List.copyOf(todos);
    }

    @Override public String type() { return "todo/write"; }
}
