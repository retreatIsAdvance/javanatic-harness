/**
 * harness-bundle-headless — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.bundle.headless {
    requires io.javanatic.harness.bundle.base;    exports io.javanatic.harness.bundle.headless;
}
