/**
 * harness-core-preset — per-session agent 能力集:preset.yml 行在 setup window
 * 内挂载到 agent scope(06 §6)。SnakeYAML 边界模块(仓库第四 YAML 边界)。
 */
module io.javanatic.harness.core.preset {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.llm.llm;
    requires org.yaml.snakeyaml;
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.preset.PresetPlugin;

    exports io.javanatic.harness.preset;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.preset;
}
