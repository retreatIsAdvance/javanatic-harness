/**
 * harness-llm-replay — scripted LLM provider: replays fixed chunk sequences
 * call by call, no network, no key (design: docs/design/10-testing.md §3).
 */
module io.javanatic.harness.llm.replay {
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;

    exports io.javanatic.harness.llm.replay;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.llm.replay;
}
