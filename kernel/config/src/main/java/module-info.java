/**
 * harness-kernel-config — 配置供给与组合行模型(ConfigService / ConfigRow /
 * ExpressionResolver 受限插值)。零第三方;YAML 解析在 bundle/base(07 §4)。
 */
module io.javanatic.harness.kernel.config {
    requires io.javanatic.harness.kernel;

    exports io.javanatic.harness.kernel.config;
}
