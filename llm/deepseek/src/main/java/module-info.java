/**
 * harness-llm-deepseek — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.llm.deepseek {
    requires io.deepseek.harness.llm.llm;
    requires io.deepseek.harness.kernel;    exports io.dsh.llm.deepseek;
}
