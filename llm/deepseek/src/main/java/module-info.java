/**
 * harness-llm-deepseek — DeepSeek 真实 Provider（OpenAI 兼容 wire，SSE 流式）。
 * 仓库第二个 Jackson 边界：模型/tool JSON 是校验过的信任边界（05 §3、08 §6）。
 */
module io.javanatic.harness.llm.deepseek {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;
    requires jdk.httpserver; // 测试用本地假服务端(keyless 覆盖)

    exports io.javanatic.harness.llm.deepseek;
}
