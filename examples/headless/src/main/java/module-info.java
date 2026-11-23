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
    requires io.javanatic.harness.shell.local;
    requires io.javanatic.harness.shell.tool;
    requires io.javanatic.harness.session.persistence;
    requires io.javanatic.harness.session.persistence.jsonl;
    requires io.javanatic.harness.workbench.projection;
    requires io.javanatic.harness.workbench.tui;
    requires io.javanatic.harness.workbench.rpc;
    requires io.javanatic.harness.interaction.approval;
    requires io.javanatic.harness.interaction.commands;
    requires io.javanatic.harness.bundle.base;
    // it38 H2:宿主按轮重组后判定后台例外(delegation 子代 / jobs 非终态)——只读 list()
    requires io.javanatic.harness.delegation;
    // it38 H2:首次 followup 前同步 bindSession（首绑即父），不依赖 CREATED 异步通知
    requires io.javanatic.harness.delegation.local;
    requires io.javanatic.harness.jobs;
    requires io.javanatic.harness.jobs.local;
    // 测试：LspReplayTest 的 fake LSP service 实现 LspService（Definition 契约）
    requires io.javanatic.harness.lsp;
    // 测试：WebReplayTest 的 fake 抓取 provider 实现 WebFetchService（Definition 契约）
    requires io.javanatic.harness.web;
    requires org.yaml.snakeyaml;
    // SIGINT 取消入口(sun.misc.Signal);非 static——jlink 镜像须含 jdk.unsupported(dist 实跑验证)
    requires jdk.unsupported;
    // it35 W3 --serve:RpcHttpServer 真运行时消费(非 static——jlink 镜像须含 jdk.httpserver)
    requires jdk.httpserver;

    exports io.javanatic.harness.examples.headless;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.examples.headless;
}
