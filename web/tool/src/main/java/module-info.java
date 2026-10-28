/**
 * harness-web-tool — 网络能力 Consumer（插件 id "web-tool"，it28）：
 * web_fetch（恒注册，抓取 provider 缺失即装配期 fail loud）与 web_search
 * （仅搜索 provider 在场且 available() 时注册）；两工具恒需审批、单字段 schema；
 * HTML 线性提取（零依赖）与源码/输出分层截断；成功结果首行恒为信任前缀，
 * 并以同一常量注册 Static(70) 提示段。零新事件：审计 = tool/call + tool/result。
 */
module io.javanatic.harness.web.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.web;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.llm.llm;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.web.tool.WebToolPlugin;

    exports io.javanatic.harness.web.tool;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.web.tool;
}
