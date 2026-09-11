/**
 * harness-bundle-base — AppBoot(组合数据化:三层行叠加/受限表达式/双向显式校验/
 * dump-config/--verify 与 policy 档位)与 base bundle 行资源(07)。SnakeYAML
 * 边界模块(仓库第三第三方,仅此模块)。
 */
module io.javanatic.harness.bundle.base {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.session.persistence;
    requires org.yaml.snakeyaml;

    exports io.javanatic.harness.boot;
}
