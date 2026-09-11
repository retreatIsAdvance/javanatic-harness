package io.javanatic.harness.todo;

import java.util.Objects;

/** 一条 todo：已校验的规范形（content 已 trim 非空、status 已收窄）。 */
public record TodoItem(String content, TodoStatus status) {

    /** @throws NullPointerException 任一字段为 null 时 */
    public TodoItem {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
    }
}
