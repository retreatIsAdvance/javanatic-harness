/**
 * harness-bundle-headless — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.bundle.headless {
    requires io.deepseek.harness.bundle.base;    exports io.dsh.bundle.headless;
}
