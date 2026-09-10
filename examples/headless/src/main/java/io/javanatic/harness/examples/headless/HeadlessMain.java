package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;

import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                         String baseUrl, String apiKeyEnv, String apiKeyLiteral, String profile,
                         String resume) {

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
        String profile = null;
        String resume = null;
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
            } else if (arg.startsWith("--profile=")) {
                profile = valueOf(arg);
            } else if (arg.startsWith("--resume=")) {
                resume = valueOf(arg);
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
            apiKeyLiteral, profile, resume);
    }

    private static String valueOf(String flag) {
        String value = flag.substring(flag.indexOf('=') + 1);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("参数缺值: " + flag);
        }
        return value;
    }

/** --profile 值:存在的文件路径直接用;否则按名字解析 ~/.harness/profiles/<name>/profile.yml。 */
    static Path resolveProfile(String value) throws Exception {
        if (value == null) {
            return Files.writeString(Files.createTempFile("jh-headless-profile", ".yml"),
                "name: headless\npolicy: standard\nbundles: [base]\nrows: []\n").normalize();
        }
        Path direct = Path.of(value);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path named = Path.of(System.getProperty("user.home"), ".harness", "profiles", value, "profile.yml");
        if (Files.isRegularFile(named)) {
            return named;
        }
        throw new IllegalArgumentException("--profile 既不是文件,也不存在 ~/.harness/profiles/" + value + "/profile.yml");
    }

    static int run(RunnerOptions options, Path workspace, Path sessions) throws Exception {
        Path profile = resolveProfile(options.profile());

        List<ConfigRowSpec> overlays = new ArrayList<>(List.of(
            new ConfigRowSpec.Replace("fs-local", Map.of("root", workspace.toString()), null),
            new ConfigRowSpec.Replace("shell-tool",
                Map.of("workspace", workspace.toString(), "timeoutSeconds", 60), null),
            new ConfigRowSpec.Replace("persistence-jsonl",
                Map.of("root", sessions.toString()), null)));
        String apiKey = options.resolvedApiKey();
        if (apiKey != null) {
            Map<String, Object> provider = new HashMap<>(Map.of(
                "name", options.provider(), "baseUrl", options.baseUrl()));
            if (options.apiKeyLiteral() != null) {
                provider.put("apiKey", options.apiKeyLiteral());
            } else {
                provider.put("apiKeyEnv", options.apiKeyEnv());
            }
            overlays.add(new ConfigRowSpec.Replace("llm-openai-compat", provider, null));
        }
        AppBoot.BootOptions boot = new AppBoot.BootOptions(profile, overlays,
            options.verify(), options.policy());
        try (Runtime rt = AppBoot.boot(boot)) {
            if (options.verify()) {
                LOG.log(Level.INFO, "verify 通过");
                return 0;
            }
            if (options.task() == null && options.resume() == null) {
                LOG.log(Level.ERROR, "缺少任务文本或 --resume=<id>（用法:java -m …HeadlessMain \"task\" 或 --resume=<sessionId>）");
                return 2;
            }
            if (apiKey == null) {
                LOG.log(Level.ERROR, "API key 未提供（--api-key=… 或环境变量 {0}；--verify 可无 key 运行）",
                    options.apiKeyEnv());
                return 2;
            }
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            prompts.register(new PromptSection(0, "You are Javanatic Harness (headless). Be terse."));
            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle;
            if (options.resume() != null) {
                // durable resume:load → seed 重建 → registry.resume 续轮号
                SessionPersistence.Loaded loaded =
                    rt.root().require(SessionPersistence.KEY).load(Session.newId(options.resume()));
                rt.root().require(SessionStore.KEY).create(rt.root(), Session.newId(options.resume()),
                    new CreateOptions(loaded.events(), loaded.header()));
                handle = agents.resume(rt.root(),
                    new ResumeAgentOptions(
                        Session.newId(options.resume()), new AgentOptions(options.provider(), options.model())));
            } else {
                handle = agents.create(rt.root(),
                    CreateAgentOptions.of(Session.newId("headless-1"),
                        new AgentOptions(options.provider(), options.model())));
            }
            Agent agent = handle.agent();
            if (options.task() != null) {
                agent.followup(UserMessage.of(options.task(), new MessageSource.User()));
            }
            agent.whenIdle().join();
            agent.session().events().forEach(entry ->
                LOG.log(Level.INFO, "{0}: {1}", entry.seq(), entry.event().type()));
            handle.disposeAndAwait();
            rt.root().require(SessionPersistence.KEY).save(agent.session());
            return 0;
        } catch (AppBoot.VerifyFailedException e) {
            e.violations().forEach(v -> LOG.log(Level.ERROR, "违规: {0}", v));
            return 1;
        }
    }
}
