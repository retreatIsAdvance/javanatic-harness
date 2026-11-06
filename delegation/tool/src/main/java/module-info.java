/**
 * 受控委派消费面：DelegationService 在场才注册 delegate / delegate_list /
 * delegate_answer / delegate_interrupt 四工具（双态，缺省组合零工具零委派零噪声）。
 * 恒需审批；问与败逐字分界渲染；树链接（childSessionId）随结果文本可见。
 */
module io.javanatic.harness.delegation.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.delegation;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.tools;

    exports io.javanatic.harness.delegation.tool;
    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.delegation.tool.DelegationToolPlugin;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.delegation.tool;
}
