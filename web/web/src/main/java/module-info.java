/**
 * harness-web-web — web capability Definition (design: docs/design/05-capability-seam.md).
 * Providers implement; consumers see only this contract. 零第三方依赖（契约自持，it28）；
 * 取消信号复用 llm.llm（AbortSignal——shell/mcp 同形）。
 */
module io.javanatic.harness.web {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.llm.llm;

    exports io.javanatic.harness.web;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.web;
}
