/**
 * harness-workbench-projection — 0.5 工作台 W1:会话日志的只读投影(五视图)。
 * 纯库模块(非 capability 三角):无 Provider/Consumer、无插件装载、无
 * ServiceLoader 双通道;结构化视图 record 在此,文本渲染归消费方
 * (headless/TUI/server)。零写路径:只消费 SessionPersistence.Loaded。
 */
module io.javanatic.harness.workbench.projection {
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;

    exports io.javanatic.harness.workbench.projection;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.workbench.projection;
}
