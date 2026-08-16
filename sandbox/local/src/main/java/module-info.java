/**
 * harness-sandbox-local — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.sandbox.local {
    requires io.javanatic.harness.sandbox.sandbox;    exports io.javanatic.harness.sandbox.local;
}
