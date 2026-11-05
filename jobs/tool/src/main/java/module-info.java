/**
 * 后台任务消费面：JobService 在场才注册 job_start / job_status / job_read /
 * job_cancel 四工具（双态，缺省组合零工具零任务零噪声）。恒需审批，无豁免出口。
 */
module io.javanatic.harness.jobs.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.jobs;
    requires io.javanatic.harness.core.tools;

    exports io.javanatic.harness.jobs.tool;
    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.jobs.tool.JobsToolPlugin;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.jobs.tool;
}
