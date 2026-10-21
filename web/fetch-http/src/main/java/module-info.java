/**
 * harness-web-fetch-http — pinned public HTTPS fetcher (plugin id "web-fetch-http", it28).
 * 一次解析、整组公网校验、钉住地址连接（SSLSocket + SNI + endpoint identification）、
 * 逐跳重定向复验、HTTP/1.1 与 chunked 解码、内容白名单与 identity 编码、有界读取。
 * 零第三方依赖。连接只 provide WebFetchService，不感知消费面。
 */
module io.javanatic.harness.web.fetch.http {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.web;
    requires io.javanatic.harness.llm.llm;

    provides io.javanatic.harness.kernel.plugin.Plugin
        with io.javanatic.harness.web.fetch.http.WebFetchHttpPlugin;

    exports io.javanatic.harness.web.fetch.http;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.web.fetch.http;
}
