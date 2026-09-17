/**
 * harness-kernel-config — 配置供给与组合行模型(ConfigService / ConfigRow /
 * ExpressionResolver 受限插值)。零第三方;YAML 解析在 bundle/base(07 §4)。
 */
module io.javanatic.harness.kernel.config {
    requires io.javanatic.harness.kernel;

    exports io.javanatic.harness.kernel.config;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.kernel.config;
}
