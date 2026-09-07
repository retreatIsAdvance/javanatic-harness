package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.fs.local.FsLocalPlugin;
import io.javanatic.harness.fs.tool.FsToolPlugin;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.deepseek.DeepSeekOptions;
import io.javanatic.harness.llm.deepseek.DeepSeekPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.persistence.jsonl.JsonlPersistencePlugin;
import io.javanatic.harness.shell.bash.local.BashLocalOptions;
import io.javanatic.harness.shell.bash.local.BashLocalPlugin;
import io.javanatic.harness.shell.tool.ShellToolPlugin;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalService;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.tools.ToolsPlugin;

import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 一次性命令行 runner。用法：
 * <pre>
 *   java -m io.javanatic.harness.examples.headless "任务文本"          # 真实跑通(需 DEEPSEEK_API_KEY)
 *   java -m io.javanatic.harness.examples.headless --verify           # 治理断言,无 key 可跑
 *   java -m io.javanatic.harness.examples.headless --verify --policy PRODUCTION
 * </pre>
 * 组合为直装(it8 bundle 层落地后本模块改为薄入口)。
 */
public final class HeadlessMain {

    private static final System.Logger LOG = System.getLogger(HeadlessMain.class.getName());

    private HeadlessMain() {
    }

    /** @param args "task" 或 --verify [--policy STANDARD|PRODUCTION] */
    public static void main(String[] args) throws Exception {
        boolean verify = false;
        Policy policy = Policy.STANDARD;
        String task = null;
        for (String arg : args) {
            if ("--verify".equals(arg)) {
                verify = true;
            } else if (arg.startsWith("--policy=")) {
                policy = Policy.valueOf(arg.substring("--policy=".length()));
            } else {
                task = arg;
            }
        }
        int exit = run(task, verify, policy,
            Files.createTempDirectory("jh-headless"),
            Files.createTempDirectory("jh-headless-sessions"));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String task, boolean verify, Policy policy, Path workspace, Path sessions)
            throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        try (Runtime rt = new Runtime()) {
            PluginLoader loader = new PluginLoader();
            loader.loadAll(rt, composition(workspace, sessions, apiKey));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            prompts.register(new PromptSection(0, "You are Javanatic Harness (headless). Be terse."));

            if (verify) {
                return verifyAndReport(rt, policy);
            }
            if (task == null) {
                LOG.log(Level.ERROR, "缺少任务文本（用法:java -m …io.javanatic.harness.examples.headless \"task\"）");
                return 2;
            }
            if (apiKey == null || apiKey.isEmpty()) {
                LOG.log(Level.ERROR, "DEEPSEEK_API_KEY 未设置（--verify 可无 key 运行）");
                return 2;
            }
            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId("headless-1"),
                    new AgentOptions("deepseek", "deepseek-chat")));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of(task, new MessageSource.User()));
            agent.whenIdle().join();
            agent.session().events().forEach(entry ->
                LOG.log(Level.INFO, "{0}: {1}", entry.seq(), entry.event().type()));
            handle.disposeAndAwait();
            rt.root().require(SessionPersistence.KEY).save(agent.session());
            return 0;
        }
    }

    static List<Plugin> composition(Path workspace, Path sessions, String apiKey) {
        List<Plugin> plugins = new java.util.ArrayList<>(List.of(
            new SessionStorePlugin(),
            new JsonlPersistencePlugin(sessions),
            new AgentPlugin(),
            new LoopGuardPlugin(new LoopGuard.Limits(50, 40)),
            new SystemPromptPlugin(),
            new LlmPlugin()));
        // --verify 无 key 时不装配 provider——治理断言不依赖模型路由
        if (apiKey != null && !apiKey.isEmpty()) {
            plugins.add(new DeepSeekPlugin(new DeepSeekOptions(
                DeepSeekOptions.DEFAULT_BASE_URL, apiKey, null, null, 2, null, null)));
        }
        // headless 无人值守:默认 AUTO;--policy PRODUCTION 校验会拒绝并提示换 ask
        plugins.addAll(List.of(
            new ApprovalAutoPlugin(),
            new ToolsPlugin(),
            new FsLocalPlugin(workspace),
            new FsToolPlugin(),
            new BashLocalPlugin(new BashLocalOptions(256 * 1024)),
            new ShellToolPlugin(workspace, Duration.ofSeconds(60)),
            new AgentLoopPlugin(Clock.systemUTC())));
        return plugins;
    }

    private static int verifyAndReport(Runtime rt, Policy policy) {
        List<String> violations = policy.check(rt.root());
        LOG.log(Level.INFO, "policy={0} 治理摘要:审批={1} 持久化durable={2} limits={3}",
            policy,
            rt.root().resolve(ApprovalService.KEY)
                .map(ApprovalService::mode).orElse(null),
            rt.root().resolve(SessionPersistence.KEY).map(SessionPersistence::durable).orElse(null),
            rt.root().resolve(LoopGuard.KEY).map(g -> g.limits()).orElse(null));
        if (!violations.isEmpty()) {
            violations.forEach(v -> LOG.log(Level.ERROR, "违规: {0}", v));
            return 1;
        }
        LOG.log(Level.INFO, "verify 通过");
        return 0;
    }

}
