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
import io.javanatic.harness.llm.openai.compat.OpenAiCompatPlugin;
import io.javanatic.harness.llm.openai.compat.TransportOptions;
import io.javanatic.harness.llm.openai.compat.VendorProfile;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性命令行 runner。用法：
 * <pre>
 *   java -m io.javanatic.harness.examples.headless "任务文本"                # 默认 deepseek(需 DEEPSEEK_API_KEY)
 *   java -m … "任务文本" --api-key-env=MOONSHOT_KEY \\
 *       --base-url=https://api.moonshot.cn/v1 --model=kimi-k2 --provider=kimi   # 任意 OpenAI 兼容厂商
 *   java -m … --verify                      # 治理断言,无 key 可跑
 *   java -m … --verify --policy=PRODUCTION
 * </pre>
 * 组合为直装(it8 bundle/ConfigService 落地后同组参数换轨 YAML,CLI 形状不变)。
 */
public final class HeadlessMain {

    private static final System.Logger LOG = System.getLogger(HeadlessMain.class.getName());

    private HeadlessMain() {
    }

    /** 运行时配置（解析自 CLI;默认值集中在此——组合位的显式 resolve 点）。 */
    record RunnerOptions(String task, boolean verify, Policy policy, String provider, String model,
                         String baseUrl, String apiKeyEnv, String apiKeyLiteral) {

        static final String DEFAULT_PROVIDER = "deepseek";
        static final String DEFAULT_MODEL = "deepseek-chat";
        static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
        static final String DEFAULT_API_KEY_ENV = "DEEPSEEK_API_KEY";

        /** 字面量优先于环境变量;均缺省时为 null(verify 路径可用,任务路径报缺 key)。 */
        String resolvedApiKey() {
            if (apiKeyLiteral != null && !apiKeyLiteral.isEmpty()) {
                return apiKeyLiteral;
            }
            String fromEnv = System.getenv(apiKeyEnv);
            return fromEnv == null || fromEnv.isEmpty() ? null : fromEnv;
        }
    }

    /** @param args "task" 与 flags（--verify/--policy=/--provider=/--model=/--base-url=/--api-key-env=/--api-key=） */
    public static void main(String[] args) throws Exception {
        RunnerOptions options = parse(args);
        int exit = run(options,
            Files.createTempDirectory("jh-headless"),
            Files.createTempDirectory("jh-headless-sessions"));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static RunnerOptions parse(String[] args) {
        boolean verify = false;
        Policy policy = Policy.STANDARD;
        String task = null;
        String provider = null;
        String model = null;
        String baseUrl = null;
        String apiKeyEnv = null;
        String apiKeyLiteral = null;
        for (String arg : args) {
            if ("--verify".equals(arg)) {
                verify = true;
            } else if (arg.startsWith("--policy=")) {
                policy = Policy.valueOf(arg.substring("--policy=".length()));
            } else if (arg.startsWith("--provider=")) {
                provider = valueOf(arg);
            } else if (arg.startsWith("--model=")) {
                model = valueOf(arg);
            } else if (arg.startsWith("--base-url=")) {
                baseUrl = valueOf(arg);
            } else if (arg.startsWith("--api-key-env=")) {
                apiKeyEnv = valueOf(arg);
            } else if (arg.startsWith("--api-key=")) {
                apiKeyLiteral = valueOf(arg);
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("未知参数: " + arg);
            } else if (task == null) {
                task = arg;
            } else {
                throw new IllegalArgumentException("任务文本只能有一个: " + arg);
            }
        }
        return new RunnerOptions(task, verify, policy,
            provider == null ? RunnerOptions.DEFAULT_PROVIDER : provider,
            model == null ? RunnerOptions.DEFAULT_MODEL : model,
            baseUrl == null ? RunnerOptions.DEFAULT_BASE_URL : baseUrl,
            apiKeyEnv == null ? RunnerOptions.DEFAULT_API_KEY_ENV : apiKeyEnv,
            apiKeyLiteral);
    }

    private static String valueOf(String flag) {
        String value = flag.substring(flag.indexOf('=') + 1);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("参数缺值: " + flag);
        }
        return value;
    }

    static int run(RunnerOptions options, Path workspace, Path sessions) throws Exception {
        String apiKey = options.resolvedApiKey();
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, composition(workspace, sessions, apiKey, options));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            prompts.register(new PromptSection(0, "You are Javanatic Harness (headless). Be terse."));

            if (options.verify()) {
                return verifyAndReport(rt, options.policy());
            }
            if (options.task() == null) {
                LOG.log(Level.ERROR, "缺少任务文本（用法:java -m …io.javanatic.harness.examples.headless \"task\"）");
                return 2;
            }
            if (apiKey == null) {
                LOG.log(Level.ERROR, "API key 未提供（--api-key=… 或环境变量 {0}；--verify 可无 key 运行）",
                    options.apiKeyEnv());
                return 2;
            }
            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle = agents.create(rt.root(),
                CreateAgentOptions.of(Session.newId("headless-1"),
                    new AgentOptions(options.provider(), options.model())));
            Agent agent = handle.agent();
            agent.followup(UserMessage.of(options.task(), new MessageSource.User()));
            agent.whenIdle().join();
            agent.session().events().forEach(entry ->
                LOG.log(Level.INFO, "{0}: {1}", entry.seq(), entry.event().type()));
            handle.disposeAndAwait();
            rt.root().require(SessionPersistence.KEY).save(agent.session());
            return 0;
        }
    }

    static List<Plugin> composition(Path workspace, Path sessions, String apiKey,
                                    RunnerOptions options) {
        List<Plugin> plugins = new ArrayList<>(List.of(
            new SessionStorePlugin(),
            new JsonlPersistencePlugin(sessions),
            new AgentPlugin(),
            new LoopGuardPlugin(new LoopGuard.Limits(50, 40)),
            new SystemPromptPlugin(),
            new LlmPlugin()));
        // 无 key（--verify）时不装配 provider——治理断言不依赖模型路由
        if (apiKey != null) {
            plugins.add(new OpenAiCompatPlugin(options.provider(),
                VendorProfile.of(options.baseUrl()),
                new TransportOptions(apiKey, null, null, 2, null, null)));
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
            rt.root().resolve(LoopGuard.KEY).map(LoopGuard::limits).orElse(null));
        if (!violations.isEmpty()) {
            violations.forEach(v -> LOG.log(Level.ERROR, "违规: {0}", v));
            return 1;
        }
        LOG.log(Level.INFO, "verify 通过");
        return 0;
    }
}
