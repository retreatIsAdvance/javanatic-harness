/**
 * 后台任务本地 Provider：进程管理（TERM→排水宽限→KILL）、输出捕获文件、
 * 看门狗超时、取消收敛、装配期 Lost 扫描（事件账 fold 唯一依据）。
 * job/snapshot 事件经持有 Session 异步落账（不进 prompt 组装）。
 */
module io.javanatic.harness.jobs.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.jobs;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;

    exports io.javanatic.harness.jobs.local;
    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.jobs.local.LocalJobsPlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec
        with io.javanatic.harness.jobs.local.JobSnapshotCodec;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.jobs.local;
}
