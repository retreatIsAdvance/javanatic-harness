/**
 * harness-llm-openai-compat — OpenAI 兼容协议的通用适配器：传输韧性(重试/退避/
 * 空闲看门狗/有界队列背压)与 wire 构造/防御性 SSE 解码在此;厂商差异收敛为
 * VendorProfile(05 实现落定)。Jackson 边界模块(模型/tool JSON 信任边界)。
 */
module io.javanatic.harness.llm.openai.compat {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;
    requires static jdk.httpserver; // 测试用本地假服务端(keyless 双形状覆盖;static=不进 jlink 镜像)
    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.llm.openai.compat.OpenAiCompatPlugin;

    exports io.javanatic.harness.llm.openai.compat;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.llm.openai.compat;
}
