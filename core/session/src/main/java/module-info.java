/**
 * harness-core-session — event-sourced session: append-only log, LoggedEvent
 * envelope, surface projection, store (design: docs/design/03-session-event-sourcing.md).
 * Zero Jackson: serialization belongs to the persistence seam.
 */
module io.javanatic.harness.core.session {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;

    exports io.javanatic.harness.session;
    exports io.javanatic.harness.session.event;
    exports io.javanatic.harness.session.message;
}
