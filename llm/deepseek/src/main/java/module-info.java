/**
 * harness-llm-deepseek — DeepSeek Provider 薄壳:DeepSeekOptions + 默认画像,
 * wire 与传输韧性在 llm/openai-compat(05 实现落定:多厂商接入 = 一个 profile)。
 */
module io.javanatic.harness.llm.deepseek {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.llm.openai.compat;
    requires jdk.httpserver; // 测试:经 seam 的假服务端冒烟

    exports io.javanatic.harness.llm.deepseek;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.llm.deepseek;
}
