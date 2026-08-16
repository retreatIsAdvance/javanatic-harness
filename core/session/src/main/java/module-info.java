/**
 * harness-core-session — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.core.session {
    requires io.javanatic.harness.kernel;    exports io.javanatic.harness.core.session;
}
