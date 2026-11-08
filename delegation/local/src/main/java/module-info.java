/**
 * 受控委派本地 Provider：子代 = durable 会话上的 in-JVM Agent（setup 回调 = 继承
 * 快照执法点）；四路终局映射（Completed+concludesTurn→NeedsParentInput /
 * Completed→Succeeded / Error→Failed / Aborted→Cancelled）；maxActive 根会话单池
 * 预留-放行-还槽；预算随 delegation/snapshot 载荷入账（回放期聚合，零运行期隐藏数）。
 */
module io.javanatic.harness.delegation.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.delegation;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.agent.loop;

    exports io.javanatic.harness.delegation.local;
    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.delegation.local.LocalDelegationPlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec
        with io.javanatic.harness.delegation.local.DelegationSnapshotCodec;
    // JUnit 同包测试运行时反射，不增加编译期可见性。
    opens io.javanatic.harness.delegation.local;
}
