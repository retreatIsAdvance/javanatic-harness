/**
 * harness-bundle-base — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.deepseek.harness.bundle.base {
    requires io.deepseek.harness.core;
    requires io.deepseek.harness.llm.deepseek;
    requires io.deepseek.harness.llm.replay;
    requires io.deepseek.harness.fs.local;
    requires io.deepseek.harness.fs.tool;
    requires io.deepseek.harness.shell.bash.local;
    requires io.deepseek.harness.shell.tool;
    requires io.deepseek.harness.session.persistence.jsonl;    exports io.dsh.bundle.base;
}
