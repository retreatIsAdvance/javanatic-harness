/**
 * harness-examples-headless — 一次性命令行 runner:直装组合 + "task" 真实跑通
 * (deepseek,需 DEEPSEEK_API_KEY) + --verify 治理断言(无 key 可跑,R4/07 §6)。
 * bundle 组合层落地(it8)后本模块改为薄入口。
 */
module io.javanatic.harness.examples.headless {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.kernel.brand;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.llm.llm;
    requires io.javanatic.harness.llm.openai.compat;
    requires io.javanatic.harness.fs.fs;
    requires io.javanatic.harness.fs.local;
    requires io.javanatic.harness.fs.tool;
    requires io.javanatic.harness.shell.shell;
    requires io.javanatic.harness.shell.bash.local;
    requires io.javanatic.harness.shell.tool;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.session.persistence.jsonl;
    requires io.javanatic.harness.interaction.approval;
    requires io.javanatic.harness.interaction.commands;
    requires io.javanatic.harness.bundle.base;
    requires org.yaml.snakeyaml;
    requires static jdk.httpserver; // 测试:本地假服务端 e2e(static=不进 jlink 镜像)

    exports io.javanatic.harness.examples.headless;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.examples.headless;
}
