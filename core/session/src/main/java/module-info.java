/**
 * harness-core-session — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.core.session {
    requires io.deepseek.harness.kernel;    exports io.dsh.core.session;
}
