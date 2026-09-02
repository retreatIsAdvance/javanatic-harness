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
}
