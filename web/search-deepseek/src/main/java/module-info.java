/**
 * harness-web-search-deepseek — DeepSeek 原生搜索 provider (plugin id
 * "web-search-deepseek", it28): Anthropic 兼容 Messages 调用 + 原生
 * {@code web_search_20250305} 工具。只把结构化 {@code web_search_tool_result}
 * 块映射为来源；无结果块即错，绝不把散文答案刮成来源。Jackson 仅此边界模块；
 * 凭据只进请求头，不入日志/错误/结果。本插件只 provide WebSearchService。
 */
module io.javanatic.harness.web.search.deepseek {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.web;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;
    requires static jdk.httpserver; // 测试:回环 HTTPS 夹具(static=不进 jlink 镜像)

    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.web.search.deepseek.WebSearchDeepSeekPlugin;

    exports io.javanatic.harness.web.search.deepseek;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.web.search.deepseek;
}
