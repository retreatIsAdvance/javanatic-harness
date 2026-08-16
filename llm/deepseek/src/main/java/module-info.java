/**
 * harness-llm-deepseek — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.llm.deepseek {
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.kernel;    exports io.javanatic.harness.llm.deepseek;
}
