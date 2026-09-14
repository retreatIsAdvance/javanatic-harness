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
    // snakeyaml 对 java.desktop 仅 requires static;默认 Yaml 构造走 java.beans 内省,
    // 运行时须在场——由本模块(snakeyaml 唯一边界)显式承担,jlink 镜像否则 ClassNotFound
    requires java.desktop;

    exports io.javanatic.harness.boot;
}
