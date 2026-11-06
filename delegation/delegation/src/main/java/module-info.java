/**
 * 受控委派 Definition：仅契约与事件形状，无子代执行、工具或第三方依赖。
 * 子代 = durable 会话上的 Agent（树结构经 childSessionId 呈现）；权限继承为
 * spawn 时点显式快照（子只窄不宽）；NeedsParentInput 是暂停不是失败。
 */
module io.javanatic.harness.delegation {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.delegation;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.delegation;
}
