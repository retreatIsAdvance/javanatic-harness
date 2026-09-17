/**
 * harness-core-session — event-sourced session: append-only log, LoggedEvent
 * envelope, surface projection, store (design: docs/design/03-session-event-sourcing.md).
 * Zero Jackson: serialization belongs to the persistence seam.
 */
module io.javanatic.harness.core.session {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.session.SessionStorePlugin;

    exports io.javanatic.harness.session;
    exports io.javanatic.harness.session.event;
    exports io.javanatic.harness.session.message;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.session;
}
