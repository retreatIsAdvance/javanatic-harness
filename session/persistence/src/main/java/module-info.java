/**
 * harness-session-persistence — 持久化 seam:SessionEventCodec(事件 ↔ JsonValue 树,
 * 纯函数)与注册表服务、SessionPersistence 接口。零 Jackson:树与 Jackson 的
 * 互译归 jsonl 后端(03 §6,序列化能力属于持久化边界)。
 */
module io.javanatic.harness.session.persistence {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.session.persistence;
}
