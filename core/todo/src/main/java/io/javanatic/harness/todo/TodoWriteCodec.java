package io.javanatic.harness.todo;

import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionEventCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * todo/write 的持久化 codec（纯函数：事件 ↔ JsonValue 树）。
 * 经 ServiceLoader 由持久化层发现（module-info provides + META-INF/services）。
 */
public final class TodoWriteCodec implements SessionEventCodec<TodoWriteEvent> {

    @Override
    public String type() {
        return "todo/write";
    }

    @Override
    public Class<TodoWriteEvent> typeClass() {
        return TodoWriteEvent.class;
    }

    @Override
    public JsonValue.Obj write(TodoWriteEvent event) {
        List<JsonValue> items = new ArrayList<>(event.todos().size());
        for (TodoItem todo : event.todos()) {
            items.add(JsonValue.object()
                .set("content", todo.content())
                .set("status", todo.status().wire())
                .build());
        }
        return JsonValue.object()
            .set("time", event.time())
            .set("todos", new JsonValue.Arr(items))
            .build();
    }

    @Override
    public TodoWriteEvent read(JsonValue.Obj body) {
        JsonValue rawTodos = body.get("todos");
        if (!(rawTodos instanceof JsonValue.Arr arr)) {
            throw new IllegalStateException("todo/write body has no todos array");
        }
        List<TodoItem> todos = new ArrayList<>(arr.items().size());
        for (JsonValue item : arr.items()) {
            todos.add(new TodoItem(
                item.get("content").asString(),
                TodoStatus.fromWire(item.get("status").asString())));
        }
        return new TodoWriteEvent(body.get("time").asLong(), todos);
    }
}
