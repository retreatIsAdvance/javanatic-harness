/**
 * harness-mcp-tool — MCP 工具消费（插件 id "mcp-tool"，requires "tools" +
 * "mcp-registry"，it27.1）：已连接 server 的当前清单 → 既有 ScopedRegistry
 * 注册（mcp__&lt;server&gt;__&lt;raw&gt; 命名，approvalExempt=false 恒真、无
 * 豁免出口）+ 轮屏障换代（TURN_STOPPING / PRE_STEP；预检先于注册面改动）+
 * PRE_STEP 落 mcp/catalog 全量快照 + 结果 256 KiB 截断 + 映射缓存 +
 * JSON Schema → ValueSchema 白名单映射（不支持构造该工具排除 + WARN；
 * Jackson 是映射边界的解析手段，同 core/tools 信任边界先例）。
 */
module io.javanatic.harness.mcp.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.mcp;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.llm.llm;
    requires com.fasterxml.jackson.databind;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.mcp.tool.McpToolPlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec with io.javanatic.harness.mcp.tool.McpCatalogCodec;

    exports io.javanatic.harness.mcp.tool;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.mcp.tool;
}
