/**
 * harness-core-system-prompt — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.core.system.prompt {
    requires io.deepseek.harness.kernel;    exports io.dsh.core.system.prompt;
}
