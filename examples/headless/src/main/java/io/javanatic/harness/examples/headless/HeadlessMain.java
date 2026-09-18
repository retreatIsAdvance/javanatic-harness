package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.agentloop.AssistantChunkEvent;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.interaction.commands.Command;
import io.javanatic.harness.interaction.commands.CommandInvocation;
import io.javanatic.harness.interaction.commands.CommandRegistry;
import io.javanatic.harness.interaction.commands.CommandResult;
import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionEvents;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 命令行 runner：带任务文本一次性执行，裸 `jh`（无任务、非 --verify）进入 REPL
 * 交互循环；`--resume` 无任务时在既有会话上进 REPL。完整用法见 {@link #USAGE}
 * （`--help` 打印到 stdout，exit 0）：默认 deepseek（需 DEEPSEEK_API_KEY）；
 * `--verify` 无 key 可跑治理断言；`--workspace=` 显式工作区（fs 围栏 / shell
 * workspace / 沙箱授予面同钉一处）；`--approval=` 切审批 Provider；
 * `--docker/--image=` 容器级隔离。组合为数据（it8 bundle/ConfigService）：
 * CLI 只把参数翻译成组合行 overlay。
 */
public final class HeadlessMain {

    private static final System.Logger LOG = System.getLogger(HeadlessMain.class.getName());

    /** `--approval` 词表：与 interaction/approval 三个 Provider id 的 "approval-" 后缀一致。 */
    private static final List<String> APPROVAL_MODES = List.of("auto", "ask", "deny");

    /** `--help` 文本。 */
    static final String USAGE = """
        Javanatic Harness headless runner —— 一次性任务与交互模式(REPL)

        用法:
          jh "任务文本" [flags]                 执行任务(需 API key)
          jh [flags]                            进入交互模式(需 API key;/help 命令, /exit 或
                                                EOF 退出;进行中的轮以 aborted 落账,可 --resume 续)
          jh --resume=<sessionId> [flags]       在既有会话上进入交互模式(带任务文本则一次性续跑)
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
          --budget=<tokens>          累计 output token 预算上限(正整数;缺省不限)。
                                     PRODUCTION 档要求非零——与 --approval=ask|deny
                                     同用,生产组合才可达
          --docker [--image=<镜像>]  容器级隔离执行(镜像须本机在场,不自动拉取)
          --resume=<sessionId>       恢复既有会话(load → seed → 续轮号)
          --provider=<名>            厂商名(缺省 deepseek)
          --model=<名>               模型(缺省 deepseek-chat)
          --base-url=<url>           OpenAI 兼容端点(缺省 https://api.deepseek.com)
          --api-key-env=<变量名>     从环境变量取 key(缺省 DEEPSEEK_API_KEY)
          --api-key=<字面量>         直接给 key(优先于环境变量;注意泄露风险)
          --profile=<文件|名字>      组合 profile;名字解析 ~/.harness/profiles/<名>/profile.yml

        REPL 说明:非 / 行作为消息发送(各成其 turn,与模型运行并行排队);/ 行走命令面
        (未知命令只提示、不送模型);--approval=ask 的裁决行走同一输入通道(不另起 stdin 读者)。

        任务结果(一次性路径;输出契约):
          stdout         任务完成时输出最终答案文本(成功但无文本时为空)
        退出码:
          0  任务完成(或 --verify 通过 / --help)
          1  --verify 违规
          2  用法错误 / 缺少 API key
          3  任务失败(厂商错误 / 守卫或预算超限;原因在 stderr)
          4  任务被取消(REPL 路径不适用:退出码 0)
        契约:成功有结果 / 失败为空——失败的 stdout 为空,诊断(会话 id、事件清单、失败
        文案、模型遗言)全部走 stderr;`out=$(jh "任务")` 取答案、按退出码判成败。

        示例:
          jh "把 README 的快速开始改准"
          jh --verify --policy=PRODUCTION --approval=ask --budget=100000
          jh "任务" --api-key-env=MOONSHOT_KEY --base-url=https://api.moonshot.cn/v1 --model=kimi-k2 --provider=kimi
          jh "任务" --docker --image=ubuntu:24.04
        """;

    private HeadlessMain() {
    }

    /** 运行时配置（解析自 CLI;默认值集中在此——组合位的显式 resolve 点）。 */
    record RunnerOptions(String task, boolean verify, Policy policy, String provider, String model,
                         String baseUrl, String apiKeyEnv, String apiKeyLiteral, String profile,
                         String resume, boolean docker, String image, Path workspace, String approval,
                         long budget, boolean help) {

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
        long budget = 0;
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
            } else if (arg.startsWith("--budget=")) {
                budget = positiveLong(valueOf(arg));
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
            apiKeyLiteral, profile, resume, docker, image, workspace, approval, budget, help);
    }

    private static String valueOf(String flag) {
        String value = flag.substring(flag.indexOf('=') + 1);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("参数缺值: " + flag);
        }
        return value;
    }

    /** --budget 契约:正整数(缺省 0 = 不限,与 loop-guard.maxBudgetTokens 语义一致)。 */
    private static long positiveLong(String value) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--budget 必须是正整数,收到: " + value, e);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("--budget 必须是正整数,收到: " + value);
        }
        return parsed;
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
        return run(options, workspace, sessions,
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    /** @param in REPL 行源（测试注入；一次性路径不读） @param out 屏幕（渲染器与 REPL 面板共写） */
    static int run(RunnerOptions options, Path workspace, Path sessions, BufferedReader in, PrintStream out)
            throws Exception {
        return run(options, workspace, sessions, in, out, System.err);
    }

    /**
     * @param err one-shot 失败诊断面（测试注入；成功路径不写）——stdout 契约 = 成功有结果 /
     *            失败为空，诊断（失败文案、模型遗言）全走 stderr
     */
    static int run(RunnerOptions options, Path workspace, Path sessions, BufferedReader in, PrintStream out,
                   PrintStream err) throws Exception {
        Path profile = resolveProfile(options.profile());
        AppBoot.BootOptions boot = new AppBoot.BootOptions(profile, buildOverlays(options, workspace, sessions),
            options.verify(), options.policy());
        try (Runtime rt = AppBoot.boot(boot)) {
            if (options.verify()) {
                LOG.log(Level.INFO, "verify 通过");
                return 0;
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
            int exit = 0;
            if (options.task() == null) {
                runRepl(rt, agent, in, out); // 裸 jh / --resume 无任务:交互循环
            } else {
                agent.followup(UserMessage.of(options.task(), new MessageSource.User()));
                agent.whenIdle().join();
                // chunk 是流式事实（S3 渲染面消费），不进 one-shot 事件清单——it13 输出形态不动
                agent.session().events().stream()
                    .filter(entry -> !(entry.event() instanceof AssistantChunkEvent))
                    .forEach(entry ->
                        LOG.log(Level.INFO, "{0}: {1}", entry.seq(), entry.event().type()));
                exit = finishOneShot(agent, out, err);
            }
            handle.disposeAndAwait();
            rt.root().require(SessionPersistence.KEY).save(agent.session());
            return exit;
        } catch (AppBoot.VerifyFailedException e) {
            e.violations().forEach(v -> LOG.log(Level.ERROR, "违规: {0}", v));
            return 1;
        }
    }

    /**
     * one-shot 终局处理（run 尾段；package-private 供测试）：成功 → stdout 最终答案
     * （空答案合法，不打）;失败 → stdout 空 + stderr 诊断块（模型遗言 + 终局文案）。
     *
     * @return 退出码 0/3/4（词表见 {@link #USAGE} 与 12 §6）
     */
    static int finishOneShot(Agent agent, PrintStream out, PrintStream err) {
        List<LoggedEvent<? extends SessionEvent>> live = liveEvents(agent.session());
        int exit = oneShotExitCode(live);
        if (exit == 0) {
            String answer = finalAnswerText(live);
            if (!answer.isEmpty()) {
                out.println(answer);
            }
            return 0;
        }
        String lastWords = finalAnswerText(live);
        if (!lastWords.isEmpty()) {
            err.println("失败前最后输出:");
            err.println(lastWords);
        }
        err.println(oneShotFailureText(live));
        return exit;
    }

    /** 本次运行新开轮的事件切片：seq >= firstLiveSeq()（seed 长度）——resume 不误判旧轮。 */
    static List<LoggedEvent<? extends SessionEvent>> liveEvents(Session session) {
        long firstLive = session.firstLiveSeq();
        return session.events().stream()
            .filter(entry -> entry.seq() >= firstLive)
            .toList();
    }

    /** 终局 → 退出码：Completed→0;Error→3;Aborted→4;无 turn/end（含未知变体）→3,fail loud。 */
    static int oneShotExitCode(List<LoggedEvent<? extends SessionEvent>> live) {
        TurnEndReason reason = lastTurnEndReason(live);
        if (reason == null) {
            return 3;
        }
        if (reason instanceof TurnEndReason.Completed) {
            return 0;
        }
        if (reason instanceof TurnEndReason.Aborted) {
            return 4;
        }
        return 3;
    }

    /** 失败诊断一行（stderr）：与 REPL 同源文案（复用 {@link StreamRenderer#failureText}）。 */
    static String oneShotFailureText(List<LoggedEvent<? extends SessionEvent>> live) {
        TurnEndReason reason = lastTurnEndReason(live);
        if (reason instanceof TurnEndReason.Error error) {
            return "任务失败: " + StreamRenderer.failureText(error);
        }
        if (reason instanceof TurnEndReason.Aborted aborted) {
            return "任务被取消: " + aborted.cause();
        }
        if (reason == null) {
            return "任务未达终局（无 turn/end），按失败处理";
        }
        return "任务失败: 未知终局变体 " + reason;
    }

    /** 新开轮最后一条 assistant/message 的文本（无文本/无消息 → 空串）。 */
    static String finalAnswerText(List<LoggedEvent<? extends SessionEvent>> live) {
        String text = "";
        for (LoggedEvent<? extends SessionEvent> entry : live) {
            if (entry.event() instanceof AssistantMessageEvent messageEvent) {
                text = messageText(messageEvent);
            }
        }
        return text;
    }

    private static String messageText(AssistantMessageEvent event) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : event.message().content()) {
            if (block instanceof TextBlock textBlock) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(textBlock.text());
            }
        }
        return text.toString();
    }

    private static TurnEndReason lastTurnEndReason(List<LoggedEvent<? extends SessionEvent>> live) {
        TurnEndReason reason = null;
        for (LoggedEvent<? extends SessionEvent> entry : live) {
            if (entry.event() instanceof TurnEnd end) {
                reason = end.reason();
            }
        }
        return reason;
    }

    /** REPL:注册内置命令 + 渲染订阅接线 + 行循环;返回后走 run 的既有 dispose/save 尾。 */
    private static void runRepl(Runtime rt, Agent agent, BufferedReader in, PrintStream out) throws IOException {
        CommandRegistry registry = rt.root().require(CommandRegistry.KEY);
        registry.register(new Command("help", "显示命令一览", invocation ->
            new CommandResult.Text(commandsText(registry))));
        registry.register(new Command("exit", "结束交互(EOF 等效)", invocation ->
            new CommandResult.Quit()));
        ReplApprovalInput approvalIn = new ReplApprovalInput();
        InputStream originalIn = System.in;
        try (StreamRenderer renderer = new StreamRenderer(out)) {
            Disposable subscription = rt.root().events().onGlobal(SessionEvents.APPENDED,
                (carrier, entry) -> {
                    if (carrier == agent.session()) {
                        renderer.onEvent(((LoggedEvent<?>) entry).event());
                    }
                });
            // 真 stdin 唯一读者 = 行循环;审批问句经 System.in 代理流读裁决行(补充 6)
            System.setIn(approvalIn);
            renderer.println("jh 交互模式 —— 输入消息回车提交;/help 命令;/exit 或 Ctrl-D 退出");
            try {
                replLoop(agent, registry, renderer, approvalIn, in);
            } finally {
                System.setIn(originalIn);
                approvalIn.close();
                subscription.close();
            }
        }
    }

    /**
     * 行循环:裁决行转交(补充 6) → 空行跳过 → `/` 行走命令面(未知命令只提示,
     * 不送模型) → 非 `/` 行 followup(各成其 turn,与模型运行并行排队)。
     * EOF ≡ /exit:进行中的轮由 dispose 链以 aborted 落账,可 --resume 续。
     */
    static void replLoop(Agent agent, CommandRegistry registry, StreamRenderer renderer,
                         ReplApprovalInput approvalIn, BufferedReader in) throws IOException {
        while (true) {
            String line = in.readLine();
            if (line == null) {
                return;
            }
            if (approvalIn.forward(line)) {
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith("/")) {
                agent.followup(UserMessage.of(line, new MessageSource.User()));
                continue;
            }
            Optional<CommandInvocation> parsed = CommandRegistry.parseCommand(line);
            if (parsed.isEmpty()) {
                renderer.println("未知命令: " + line.strip() + "（/help 查看可用命令）");
                continue;
            }
            CommandInvocation invocation = parsed.get();
            if (registry.find(invocation.name()).isEmpty()) {
                renderer.println("未知命令: /" + invocation.name() + "（/help 查看可用命令）");
                continue;
            }
            CommandResult result;
            try {
                result = registry.execute(invocation, agent.session());
            } catch (RuntimeException e) {
                // registry 已落 command/done(ok=false);这里只补屏幕提示
                renderer.println("命令失败: /" + invocation.name() + " — " + e.getMessage());
                continue;
            }
            if (result instanceof CommandResult.Quit) {
                return;
            }
            if (result instanceof CommandResult.Text text) {
                renderer.println(text.content());
            }
        }
    }

    /** /help 文本:执行时读注册表(注册晚于 /help 也能列出;名升序,list 已保证)。 */
    static String commandsText(CommandRegistry registry) {
        StringBuilder text = new StringBuilder("命令:");
        for (Command command : registry.list()) {
            text.append("\n  /").append(command.name()).append(" — ").append(command.summary());
        }
        return text.toString();
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
        if (options.budget() > 0) {
            // PRODUCTION 档可达条件之一:预算上限进 loop-guard 行
            overlays.add(new ConfigRowSpec.Replace("loop-guard",
                Map.of("maxBudgetTokens", options.budget()), null));
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
