/**
 * harness-session-persistence-jsonl — JSONL 后端:Jackson ↔ JsonValue 互译、
 * 核心 10 事件 codec、逐行信封(ignorable 决策)、header.json、增量追加与
 * load 重建(seq 连续校验)。
 */
module io.javanatic.harness.session.persistence.jsonl {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;
    requires com.fasterxml.jackson.databind;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;

    exports io.javanatic.harness.session.persistence.jsonl;
}
