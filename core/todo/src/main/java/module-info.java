/**
 * harness-core-todo — todo_write 工具与 todo/write 扩展事件（dsh tool-todo 的
 * JH 形状）：整表替换、每次调用落一条全量快照、回放末值胜。工具经
 * ToolExecutionContext.session() 直写领域事件（审计对仍归 executor，R2）；
 * codec 经 ServiceLoader 由持久化层发现（provides 双注册）。
 */
module io.javanatic.harness.core.todo {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.session.persistence;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.todo.TodoPlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec with io.javanatic.harness.todo.TodoWriteCodec;

    exports io.javanatic.harness.todo;
}
