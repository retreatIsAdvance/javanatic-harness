/**
 * 后台任务 Definition：仅契约与事件形状，无进程、工具或第三方依赖。
 * 任务由所属会话持有；进程生命周期与持久诚实（Lost 标记）归 Provider。
 */
module io.javanatic.harness.jobs {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;

    exports io.javanatic.harness.jobs;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.jobs;
}
