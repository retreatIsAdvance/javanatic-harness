/**
 * JPMS aggregate: re-exports the core submodules so consumers can
 * `requires io.deepseek.harness.core` in one line (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.core {
    requires transitive io.deepseek.harness.core.session;
    requires transitive io.deepseek.harness.core.system.prompt;
    requires transitive io.deepseek.harness.core.tools;
    requires transitive io.deepseek.harness.core.agent;
    requires transitive io.deepseek.harness.core.agent.loop;}
