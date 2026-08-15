/**
 * harness-sandbox-sandbox — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.sandbox.sandbox {
    requires io.deepseek.harness.kernel;    exports io.dsh.sandbox.sandbox;
}
