/**
 * harness-core-agent — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.core.agent {
    requires io.deepseek.harness.kernel;    exports io.dsh.core.agent;
}
