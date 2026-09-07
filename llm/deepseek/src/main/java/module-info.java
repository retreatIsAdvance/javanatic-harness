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
}
