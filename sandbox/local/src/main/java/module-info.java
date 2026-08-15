/**
 * harness-sandbox-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.sandbox.local {
    requires io.deepseek.harness.sandbox.sandbox;    exports io.dsh.sandbox.local;
}
