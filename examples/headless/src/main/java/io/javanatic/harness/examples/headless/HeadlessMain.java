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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一次性命令行 runner。完整用法见 {@link #USAGE}（`--help` 打印到 stdout，exit 0）：
 * 默认 deepseek（需 DEEPSEEK_API_KEY）；`--verify` 无 key 可跑治理断言；
 * `--workspace=` 显式工作区（fs 围栏 / shell workspace / 沙箱授予面同钉一处）；
 * `--approval=` 切审批 Provider；`--docker/--image=` 容器级隔离。
 * 组合为数据（it8 bundle/ConfigService）：CLI 只把参数翻译成组合行 overlay。
 */
public final class HeadlessMain {

    private static final System.Logger LOG = System.getLogger(HeadlessMain.class.getName());

    /** `--approval` 词表：与 interaction/approval 三个 Provider id 的 "approval-" 后缀一致。 */
    private static final List<String> APPROVAL_MODES = List.of("auto", "ask", "deny");

    /** `--help` 文本。 */
    static final String USAGE = """
        Javanatic Harness headless runner —— 一次性执行任务

        用法:
          jh "任务文本" [flags]                 执行任务(需 API key)
          jh --resume=<sessionId> "任务文本"    恢复既有会话续跑
          jh --verify [flags]                  组合与治理断言(无 key 可跑,exit 0/1)
          jh --help                            显示本说明

        flags:
          -h, --help                 显示本说明(exit 0)
          --workspace=<dir>          工作区:须为已存在目录;fs 围栏 / shell workspace /
                                     沙箱授予面三处都钉到此目录。缺省:新建临时目录
          --verify                   只跑组合期断言,不创建 agent、不需要 API key
          --policy=STANDARD|PRODUCTION
                                     治理档;PRODUCTION 拒 AUTO 审批 / 非耐久持久化 /
                                     零限额组合,违规逐项报告(exit 1)
          --approval=auto|ask|deny   审批 Provider 三选一(缺省 auto);ask 走 stdin 人闸,
                                     非交互环境(EOF)按拒绝
          --docker [--image=<镜像>]  容器级隔离执行(镜像须本机在场,不自动拉取)
          --resume=<sessionId>       恢复既有会话(load → seed → 续轮号)
          --provider=<名>            厂商名(缺省 deepseek)
          --model=<名>               模型(缺省 deepseek-chat)
          --base-url=<url>           OpenAI 兼容端点(缺省 https://api.deepseek.com)
          --api-key-env=<变量名>     从环境变量取 key(缺省 DEEPSEEK_API_KEY)
          --api-key=<字面量>         直接给 key(优先于环境变量;注意泄露风险)
          --profile=<文件|名字>      组合 profile;名字解析 ~/.harness/profiles/<名>/profile.yml

        示例:
          jh "把 README 的快速开始改准"
          jh --verify --policy=PRODUCTION --approval=ask
          jh "任务" --api-key-env=MOONSHOT_KEY --base-url=https://api.moonshot.cn/v1 --model=kimi-k2 --provider=kimi
          jh "任务" --docker --image=ubuntu:24.04
        """;

    private HeadlessMain() {
    }

    /** 运行时配置（解析自 CLI;默认值集中在此——组合位的显式 resolve 点）。 */
    record RunnerOptions(String task, boolean verify, Policy policy, String provider, String model,
                         String baseUrl, String apiKeyEnv, String apiKeyLiteral, String profile,
                         String resume, boolean docker, String image, Path workspace, String approval,
                         boolean help) {

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

    /** @param args 任务文本与 flags（完整清单见 {@link #USAGE}） */
    public static void main(String[] args) throws Exception {
        RunnerOptions options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.ERROR, "{0}（用法见 --help）", e.getMessage());
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(USAGE);
            return;
        }
        Path workspace = options.workspace() != null
            ? options.workspace()
            : Files.createTempDirectory("jh-headless");
        int exit;
        try {
            exit = run(options, workspace, Path.of(System.getProperty("user.home"), ".harness", "sessions"));
        } catch (IllegalArgumentException e) {
            LOG.log(Level.ERROR, "{0}", e.getMessage());
            System.exit(2);
            return;
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static RunnerOptions parse(String[] args) {
        boolean verify = false;
        boolean docker = false;
        boolean help = false;
        String image = null;
        Policy policy = Policy.STANDARD;
        String task = null;
        String provider = null;
        String model = null;
        String baseUrl = null;
        String apiKeyEnv = null;
        String apiKeyLiteral = null;
        String profile = null;
        String resume = null;
        Path workspace = null;
        String approval = null;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
            } else if ("--verify".equals(arg)) {
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
            } else if (arg.startsWith("--workspace=")) {
                workspace = existingDirectory(valueOf(arg));
            } else if (arg.startsWith("--approval=")) {
                approval = valueOf(arg);
                if (!APPROVAL_MODES.contains(approval)) {
                    throw new IllegalArgumentException(
                        "--approval 只支持 " + String.join("|", APPROVAL_MODES) + ",收到: " + approval);
                }
            } else if ("--docker".equals(arg)) {
                docker = true;
            } else if (arg.startsWith("--image=")) {
                image = valueOf(arg);
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
        if (image != null && !docker) {
            throw new IllegalArgumentException("--image 需与 --docker 同用");
        }
        return new RunnerOptions(task, verify, policy,
            provider == null ? RunnerOptions.DEFAULT_PROVIDER : provider,
            model == null ? RunnerOptions.DEFAULT_MODEL : model,
            baseUrl == null ? RunnerOptions.DEFAULT_BASE_URL : baseUrl,
            apiKeyEnv == null ? RunnerOptions.DEFAULT_API_KEY_ENV : apiKeyEnv,
            apiKeyLiteral, profile, resume, docker, image, workspace, approval, help);
    }

    private static String valueOf(String flag) {
        String value = flag.substring(flag.indexOf('=') + 1);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("参数缺值: " + flag);
        }
        return value;
    }

    /** --workspace 契约:必须是已存在目录(fail loud,不用"顺手创建"兜住手误)。 */
    private static Path existingDirectory(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("--workspace 不是已存在目录: " + path);
        }
        return path;
    }

    /** 每次运行新会话 id(07 §7):时间戳 + 短随机,不再复用固定 id 混写同一 log.jsonl。 */
    static String newRunSessionId() {
        return "headless-" + System.currentTimeMillis() + "-"
            + Long.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
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
        AppBoot.BootOptions boot = new AppBoot.BootOptions(profile, buildOverlays(options, workspace, sessions),
            options.verify(), options.policy());
        try (Runtime rt = AppBoot.boot(boot)) {
            if (options.verify()) {
                LOG.log(Level.INFO, "verify 通过");
                return 0;
            }
            if (options.task() == null && options.resume() == null) {
                LOG.log(Level.ERROR, "缺少任务文本或 --resume=<id>（用法见 --help）");
                return 2;
            }
            String apiKey = options.resolvedApiKey();
            if (apiKey == null) {
                LOG.log(Level.ERROR, "API key 未提供（--api-key=… 或环境变量 {0}；--verify 可无 key 运行）",
                    options.apiKeyEnv());
                return 2;
            }
            String sessionId = options.resume() != null ? options.resume() : newRunSessionId();
            LOG.log(Level.INFO, options.resume() != null ? "resume session={0}" : "session={0}", sessionId);
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            prompts.register(new PromptSection.Static(0, "You are Javanatic Harness (headless). Be terse."));
            AgentRegistry agents = rt.root().require(AgentRegistry.KEY);
            AgentHandle handle;
            if (options.resume() != null) {
                // durable resume:load → seed 重建 → registry.resume 续轮号
                SessionPersistence.Loaded loaded =
                    rt.root().require(SessionPersistence.KEY).load(Session.newId(sessionId));
                rt.root().require(SessionStore.KEY).create(rt.root(), Session.newId(sessionId),
                    new CreateOptions(loaded.events(), loaded.header()));
                handle = agents.resume(rt.root(),
                    new ResumeAgentOptions(
                        Session.newId(sessionId), new AgentOptions(options.provider(), options.model())));
            } else {
                handle = agents.create(rt.root(),
                    CreateAgentOptions.of(Session.newId(sessionId),
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

    /** CLI 参数 → 组合行 overlay（run 与验收测试共用;审批三 Provider 的互斥收口在此）。 */
    static List<ConfigRowSpec> buildOverlays(RunnerOptions options, Path workspace, Path sessions) {
        List<ConfigRowSpec> overlays = new ArrayList<>(List.of(
            new ConfigRowSpec.Replace("fs-local", Map.of("root", workspace.toString()), null),
            new ConfigRowSpec.Replace("shell-tool",
                Map.of("workspace", workspace.toString(), "timeoutSeconds", 60), null),
            // 沙箱授予面与 fs/shell 围栏钉到同一工作区—— Seatbelt 不比围栏宽
            new ConfigRowSpec.Replace("sandbox-policy",
                Map.of("mode", "workspace-write", "workspace", workspace.toString()), null),
            new ConfigRowSpec.Replace("persistence-jsonl",
                Map.of("root", sessions.toString()), null)));
        if (options.docker()) {
            // 环境级隔离：禁本机 bash 行(disabled 是表达式串,"true" 求值为裸真操作数 →
            // resolve 期整行滤除),启容器执行(镜像本机须在场——不自动拉取)
            overlays.add(new ConfigRowSpec.Replace("shell-bash-local", Map.of(), "true"));
            overlays.add(new ConfigRowSpec.Replace("shell-docker",
                Map.of("image", options.image() == null ? "ubuntu:24.04" : options.image()), null));
        }
        if (options.approval() != null) {
            // 审批三 Provider 互斥(同 scope 双 ApprovalService 由 kernel fail loud):
            // 选中的整行替换启用,其余两行经 disabled="true" 表达式滤除
            for (String mode : APPROVAL_MODES) {
                overlays.add(new ConfigRowSpec.Replace("approval-" + mode, Map.of(),
                    mode.equals(options.approval()) ? null : "true"));
            }
        }
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
        return overlays;
    }
}
