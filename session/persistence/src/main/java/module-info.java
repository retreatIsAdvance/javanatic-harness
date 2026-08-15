/**
 * harness-session-persistence — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.session.persistence {
    requires io.deepseek.harness.core.session;
    requires io.deepseek.harness.kernel;    exports io.dsh.session.persistence;
}
