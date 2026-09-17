/**
 * harness-examples-agent-spine — keyless vertical-slice example: replay model +
 * fs tools + full journaling; the R1 replay-hash closed loop is exercised here.
 */
module io.javanatic.harness.examples.agent.spine {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.llm.replay;
    requires io.javanatic.harness.fs.fs;
    requires io.javanatic.harness.fs.local;
    requires io.javanatic.harness.fs.tool;
    requires io.javanatic.harness.core.plan;
    requires io.javanatic.harness.sandbox.sandbox;
    requires io.javanatic.harness.sandbox.local;
    requires io.javanatic.harness.sandbox.policy;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.session.persistence.jsonl;
    exports io.javanatic.harness.examples.agent.spine;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.examples.agent.spine;
}
