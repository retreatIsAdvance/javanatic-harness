package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agent.Agent;
import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.agentloop.AssistantChunkEvent;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.boot.AppBoot;
import io.javanatic.harness.boot.Policy;
import io.javanatic.harness.interaction.commands.Command;
import io.javanatic.harness.interaction.commands.CommandInvocation;
import io.javanatic.harness.interaction.commands.CommandRegistry;
import io.javanatic.harness.interaction.commands.CommandResult;
import io.javanatic.harness.kernel.config.CompositionManifest;
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
import io.javanatic.harness.session.persistence.SessionSummary;
import io.javanatic.harness.session.persistence.WriterLockException;
import io.javanatic.harness.systemprompt.PromptSection;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ApprovalService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * 命令行 runner：带任务文本一次性执行，裸 `jh`（无任务、非 --verify）进入 REPL
 * 交互循环；`--resume` 无任务时在既有会话上进 REPL。完整用法见 {@link #USAGE}
 * （`--help` 打印到 stdout，exit 0）：默认 deepseek（需 DEEPSEEK_API_KEY）；
 * `--verify` 无 key 可跑治理断言；`--workspace=` 显式工作区（提示词 cwd / fs 围栏 /
 * shell workspace / 沙箱授予面四处同源）；`--approval=` 切审批 Provider；
 * `--docker/--image=` 容器级隔离。组合为数据（it8 bundle/ConfigService）：
 * CLI 只把参数翻译成组合行 overlay。
 */
public final class HeadlessMain {

    private static final System.Logger LOG = System.getLogger(HeadlessMain.class.getName());

    /** `--approval` 词表：与 interaction/approval 三个 Provider id 的 "approval-" 后缀一致。 */
    private static final List<String> APPROVAL_MODES = List.of("auto", "ask", "deny");

    /** 会话列举时间戳：本地时区 ISO-8601（带偏移；同一次运行内 created/last 可比）。 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** `--help` 文本。 */
    static final String USAGE = """
        Javanatic Harness headless runner —— 一次性任务与交互模式(REPL)

        用法:
          jh "任务文本" [flags]                 执行任务(需 API key)
          jh [flags]                            进入交互模式(需 API key;/help 命令;/exit、EOF 或
                                                空闲 Ctrl-C 退出;进行中的轮 Ctrl-C 取消,以
                                                aborted 落账,可 --resume 续)
          jh --resume=<sessionId> [flags]       在既有会话上进入交互模式(带任务文本则一次性续跑)
          jh --sessions[=<N>]                  列举会话(id/活动时间/事件数/写者状态/cwd;
                                                无 key 可跑;续跑用行内 id)
          jh --verify [flags]                  组合与治理断言(无 key 可跑,exit 0/1;
                                                通过时 stdout 打印 07 §6 治理摘要)
          jh --help                            显示本说明

        flags:
          -h, --help                 显示本说明(exit 0)
          --workspace=<dir>          工作区:须为已存在目录;提示词 cwd / fs 围栏 / shell
                                     workspace / 沙箱授予面四处都钉到此目录。缺省:新建临时目录
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
          --resume=<sessionId>       恢复既有会话(load → seed → 续轮号;会话被另一写者
                                     占用时拒绝,写者锁冲突 → exit 3;id 不存在 → exit 3 +
                                     指引,用 --sessions 查可用 id)
          --sessions[=<N>]           列举会话:每行 <id> created=… last=… events=… <busy|idle>
                                     cwd=…,按最后活动降序(缺省 20 条;截断时尾行提示
                                     --sessions=<N> 放大上限);与任务文本 / --resume / --verify 互斥
          --provider=<名>            厂商名(缺省 deepseek)
          --model=<名>               模型(缺省 deepseek-chat)
          --base-url=<url>           OpenAI 兼容端点(缺省 https://api.deepseek.com)
          --api-key-env=<变量名>     从环境变量取 key(缺省 DEEPSEEK_API_KEY)
          --api-key=<字面量>         直接给 key(优先于环境变量;注意泄露风险)
          --profile=<文件|名字>      组合 profile;名字解析 ~/.harness/profiles/<名>/profile.yml

        REPL 说明:非 / 行作为消息发送(各成其 turn,与模型运行并行排队);/ 行走命令面
        (未知命令只提示、不送模型);--approval=ask 的裁决行走同一输入通道(不另起 stdin 读者);
        每轮结束渲染一行轮末统计(stats: 见下)。

        Ctrl-C(SIGINT) 语义:
          一次性任务 取消当前轮:收敛后以 turn/end(aborted) 落账、退出码 4(stdout 为空);
                     再按一次 = 强制退出(130;取消卡在收敛时的逃生门)
          交互模式  取消当前轮并清空排队输入,REPL 继续(不退出);空闲时 Ctrl-C 退出(同 /exit);
                     再按一次 = 强制退出(130)
          注意:取消只对协作面生效——整批工具无视取消并正常返回时,turn 以 Completed 收口
          (退出码 0);不合作工具会拖住收敛,强制退出即为此备。

        任务结果(一次性路径;输出契约):
          stdout         任务完成时输出最终答案文本(成功但无文本时为空)
          stderr         成功时另打一行轮末统计,与 REPL 同形:
                         stats: turn=… steps=… tokens_in=… tokens_out=… elapsed=…s
        退出码:
          0  任务完成(或 --verify 通过 / --sessions 成功 / --help)
          1  --verify 违规
          2  用法错误 / 缺少 API key
          3  任务失败(厂商错误 / 守卫或预算超限 / --resume 会话不存在或写者锁冲突;原因在 stderr)
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
                         long budget, boolean help, OptionalInt sessions) {

        static final String DEFAULT_PROVIDER = "deepseek";
        static final String DEFAULT_MODEL = "deepseek-chat";
        static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
        static final String DEFAULT_API_KEY_ENV = "DEEPSEEK_API_KEY";

        /** --sessions 缺省条数（有界列举;截断时尾行提示放大上限）。 */
        static final int DEFAULT_SESSIONS_LIMIT = 20;

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
        OptionalInt sessions = OptionalInt.empty();
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
            } else if ("--sessions".equals(arg)) {
                sessions = OptionalInt.of(RunnerOptions.DEFAULT_SESSIONS_LIMIT);
            } else if (arg.startsWith("--sessions=")) {
                sessions = OptionalInt.of(positiveInt(valueOf(arg)));
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
        if (sessions.isPresent()) {
            // 列举是只读旁路:与"要跑什么"的参数同用即为用法错误(不猜意图)
            if (task != null) {
                throw new IllegalArgumentException("--sessions 与任务文本互斥（列举不执行任务）");
            }
            if (resume != null) {
                throw new IllegalArgumentException("--sessions 与 --resume 互斥（列举请另开一次运行）");
            }
            if (verify) {
                throw new IllegalArgumentException("--sessions 与 --verify 互斥（一次只做一件事）");
            }
        }
        return new RunnerOptions(task, verify, policy,
            provider == null ? RunnerOptions.DEFAULT_PROVIDER : provider,
            model == null ? RunnerOptions.DEFAULT_MODEL : model,
            baseUrl == null ? RunnerOptions.DEFAULT_BASE_URL : baseUrl,
            apiKeyEnv == null ? RunnerOptions.DEFAULT_API_KEY_ENV : apiKeyEnv,
            apiKeyLiteral, profile, resume, docker, image, workspace, approval, budget, help, sessions);
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

    /** --sessions=<N> 契约:正整数(条数上限,截断时有提示,不存在"无限列举")。 */
    private static int positiveInt(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--sessions 必须是正整数,收到: " + value, e);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("--sessions 必须是正整数,收到: " + value);
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

    /** --resume 占用识别:直接异常即 {@link WriterLockException};经 notifyOrdered 传播的
     *  包装形取根因——两处形状归一,非占用异常返回 null(原样上抛)。 */
    private static WriterLockException writerLockCause(Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        return cause instanceof WriterLockException lock ? lock : null;
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
        AppBoot.Booted booted;
        try {
            booted = AppBoot.bootReported(boot);
        } catch (AppBoot.VerifyFailedException e) {
            e.violations().forEach(v -> LOG.log(Level.ERROR, "违规: {0}", v));
            return 1;
        }
        try (Runtime rt = booted.runtime()) {
            if (options.verify()) {
                governanceSummary(booted, options, rt.root()).forEach(out::println);
                return 0;
            }
            if (options.sessions().isPresent()) {
                // 只读旁路:不进 API key 检查、不建 agent——列举的是落盘事实
                sessionListing(rt.root(), options.sessions().getAsInt()).forEach(out::println);
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
                SessionPersistence.Loaded loaded;
                try {
                    loaded = rt.root().require(SessionPersistence.KEY).load(Session.newId(sessionId));
                    rt.root().require(SessionStore.KEY).create(rt.root(), Session.newId(sessionId),
                        new CreateOptions(loaded.events(), loaded.header()));
                } catch (NoSuchElementException e) {
                    // 未知 id 不是崩溃面:给恢复指引(可用 id 在 --sessions),与写者锁同为 exit 3
                    LOG.log(Level.ERROR, "{0}", unknownSessionText(sessionId));
                    return 3;
                } catch (IOException | CompletionException e) {
                    // 写者锁冲突:load 探测直抛 WriterLockException;CREATED-attach 获取锁失败
                    // 经 notifyOrdered 包装成 CompletionException——两形归一,fail loud 出口一致
                    WriterLockException lock = writerLockCause(e);
                    if (lock == null) {
                        throw e;
                    }
                    LOG.log(Level.ERROR,
                        "会话被另一写者占用（写者锁冲突）: {0}；先结束占用该会话的进程/实例，再重试 --resume={1}",
                        lock.getMessage(), sessionId);
                    return 3;
                }
                handle = agents.resume(rt.root(),
                    new ResumeAgentOptions(
                        Session.newId(sessionId), new AgentOptions(options.provider(), options.model())));
            } else {
                handle = agents.create(rt.root(),
                    CreateAgentOptions.of(Session.newId(sessionId),
                        new AgentOptions(options.provider(), options.model())));
            }
            Agent agent = handle.agent();
            SigintPolicy sigint = new SigintPolicy(() -> agent.whenIdle(),
                () -> agent.cancel(new AgentCancelCause.User(), CancelOptions.DEFAULT),
                options.task() == null,
                // java.lang.Runtime 全限定:本作用域 Runtime 是 kernel scope 类型(import 冲突)
                code -> java.lang.Runtime.getRuntime().halt(code),
                message -> (options.task() == null ? out : err).println(message));
            Runnable restoreSigint = bindSigint(sigint);
            int exit = 0;
            try {
                if (options.task() == null) {
                    runRepl(rt, agent, sessionId, in, out, sigint); // 裸 jh / --resume 无任务:交互循环
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
            } finally {
                restoreSigint.run();
            }
            handle.disposeAndAwait();
            rt.root().require(SessionPersistence.KEY).save(agent.session());
            return exit;
        }
    }

    /**
     * one-shot 终局处理（run 尾段；package-private 供测试）：成功 → stdout 最终答案
     * （空答案合法，不打）+ stderr 一行轮末统计（与 REPL 同形，stdout 契约不动）;
     * 失败 → stdout 空 + stderr 诊断块（模型遗言 + 终局文案）。
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
            err.println(TurnStats.of(live).line());
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

    /**
     * 07 §6 治理摘要（--verify 成功路径 stdout；承诺「来自实现自述」）：组合计数 +
     * 审批模式 / 审计耐久 / 停止上限——模式、耐久与 limits 全部读实现
     * （{@code ApprovalService.mode()} 等），行数/发现数读 boot 与清单；不印文档常量。
     * 走到这里 Policy.check 已过——治理服务缺失在 boot 期即违规（exit 1）。
     */
    static List<String> governanceSummary(AppBoot.Booted booted, RunnerOptions options, Scope root) {
        CompositionManifest manifest = root.require(CompositionManifest.KEY);
        ApprovalService approval = root.require(ApprovalService.KEY);
        SessionPersistence persistence = root.require(SessionPersistence.KEY);
        LoopGuard.Limits limits = root.require(LoopGuard.KEY).limits();
        CompositionManifest.Row audit = mountedRow(manifest, "persistence-");
        long budget = limits.maxBudgetTokens();
        return List.of(
            "Profile: " + booted.profileName() + "   policy: " + options.policy(),
            "  composition: " + manifest.rows().size() + " rows, " + booted.discovered()
                + " discovered, " + booted.unreferenced() + " unreferenced",
            "  approval: " + approval.mode() + " (" + mountedRow(manifest, "approval-").plugin() + ")",
            "  audit: " + audit.plugin().replaceFirst("^persistence-", "") + " "
                + configText(audit, "root") + (persistence.durable() ? " (durable)" : " (non-durable)"),
            "  stop: max-turns=" + limits.maxTurns() + " max-steps=" + limits.maxStepsPerTurn()
                + " budget=" + (budget > 0 ? Long.toString(budget) : "unlimited"));
    }

    /**
     * it22 会话列举（--sessions;只读旁路,无 key 可跑）:头行 = 列出/总数 + 落盘根
     * （根读组合自述,与治理摘要同一来源;列举的是落盘事实而非文档口径）,每会话一行
     * （id / 创建 / 末次活动 / 事件数 / 写者状态 / cwd）,按最后活动降序;截断时尾行
     * 给放大口径。行内 id 原样可喂 --resume——不需手工翻 JSONL 找会话。
     */
    static List<String> sessionListing(Scope root, int limit) throws IOException {
        CompositionManifest.Row audit = mountedRow(root.require(CompositionManifest.KEY), "persistence-");
        SessionPersistence.Catalog catalog = root.require(SessionPersistence.KEY).list(limit);
        List<String> lines = new ArrayList<>();
        lines.add("sessions: " + catalog.sessions().size() + "/" + catalog.total()
            + "   root=" + configText(audit, "root"));
        for (SessionSummary summary : catalog.sessions()) {
            lines.add("  " + summary.id().value()
                + "  created=" + timestamp(summary.createdAt())
                + "  last=" + timestamp(summary.lastActivityMillis())
                + "  events=" + summary.eventCount()
                + "  " + (summary.busy() ? "busy" : "idle")
                + "  cwd=" + summary.cwd().orElse("-"));
        }
        if (catalog.sessions().size() < catalog.total()) {
            lines.add("  截断: 仅列前 " + catalog.sessions().size() + " 条（共 " + catalog.total()
                + "）—— --sessions=<N> 放大上限");
        }
        return lines;
    }

    /** epoch millis → 本地时区 ISO-8601（带偏移）。 */
    private static String timestamp(long millis) {
        return TIMESTAMP.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** 未知 --resume id 的诊断 + 恢复指引（stderr;id 原样回显,可用 id 在 --sessions）。 */
    static String unknownSessionText(String sessionId) {
        return "会话不存在: " + sessionId + "；用 jh --sessions 查看可用会话，再 --resume=<id>";
    }

    /** 清单中首个前缀挂载行（治理摘要 / 会话列举按插件 id 定位挂的是哪个实现；缺失 fail loud）。 */
    private static CompositionManifest.Row mountedRow(CompositionManifest manifest, String prefix) {
        return manifest.rows().stream()
            .filter(row -> row.plugin().startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("组合自述失败:组合清单无 " + prefix + "* 行"));
    }

    /** 行 config 的字符串值（缺失 fail loud——摘要/列举不猜）。 */
    private static String configText(CompositionManifest.Row row, String key) {
        Object value = row.config().get(key);
        if (value == null) {
            throw new IllegalStateException("组合自述失败:行 " + row.plugin() + " 缺 config " + key);
        }
        return value.toString();
    }

    /** REPL:注册内置命令 + 渲染订阅接线 + 行循环;返回后走 run 的既有 dispose/save 尾。 */
    private static void runRepl(Runtime rt, Agent agent, String sessionId, BufferedReader in, PrintStream out,
                                SigintPolicy sigint) {
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
            // 会话身份印在首屏:退出后可原样 --resume（it22 恢复指引;--sessions 复核 id）
            renderer.println("session=" + sessionId + "（续跑: jh --resume=" + sessionId + "）");
            try {
                replLoop(agent, registry, renderer, approvalIn, in, sigint);
            } finally {
                System.setIn(originalIn);
                approvalIn.close();
                subscription.close();
            }
        }
    }

    /**
     * 行循环:伴生虚拟线程做 stdin 阻塞读(push 进队列),主循环 50ms 轮询队列与 SIGINT
     * 退出请求——阻塞读不可中断,可轮询形状(同 {@code ApprovalPrompt.stdin})才能让空闲期
     * 的 Ctrl-C 不等下一行就退出。裁决行转交(补充 6) → 空行跳过 → `/` 行走命令面
     * (未知命令只提示,不送模型) → 非 `/` 行 followup(各成其 turn,与模型运行并行排队)。
     * EOF ≡ /exit:进行中的轮由 dispose 链以 aborted 落账,可 --resume 续。
     */
    static void replLoop(Agent agent, CommandRegistry registry, StreamRenderer renderer,
                         ReplApprovalInput approvalIn, BufferedReader in, SigintPolicy sigint) {
        BlockingQueue<Optional<String>> lines = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("jh-repl-reader").start(() -> readLines(in, lines));
        long pollMillis = 50; // 退出请求轮询间隔:静止期 Ctrl-C 的响应时延上界
        while (true) {
            Optional<String> next;
            try {
                next = lines.poll(pollMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (next == null) {
                if (sigint.exitRequested()) {
                    return; // 静止期 Ctrl-C ≡ /exit
                }
                continue;
            }
            if (next.isEmpty()) {
                return;
            }
            String line = next.get();
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

    /** 伴生读线程体:阻塞 readLine → 队列;读尽/读失败一律 EOF(退出等走行循环既有路径)。 */
    private static void readLines(BufferedReader in, BlockingQueue<Optional<String>> lines) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(Optional.of(line));
            }
        } catch (IOException e) {
            // 读失败 ≡ EOF:行循环退出,未决裁决得 -1(拒绝语义,与既有一致)
        }
        lines.add(Optional.empty());
    }

    /** /help 文本:执行时读注册表(注册晚于 /help 也能列出;名升序,list 已保证)。 */
    static String commandsText(CommandRegistry registry) {
        StringBuilder text = new StringBuilder("命令:");
        for (Command command : registry.list()) {
            text.append("\n  /").append(command.name()).append(" — ").append(command.summary());
        }
        return text.toString();
    }

    /** 绑定 SIGINT → 策略;返回还原器(恢复上一个 handler)。信号不可用(-Xrs 等)降级并告警。 */
    private static Runnable bindSigint(SigintPolicy policy) {
        try {
            SignalHandler previous = Signal.handle(new Signal("INT"), signal -> policy.onSignal());
            return () -> Signal.handle(new Signal("INT"), previous);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            LOG.log(Level.WARNING, "SIGINT 处理不可用（Ctrl-C 将直杀进程）: {0}", e.getMessage());
            return () -> { };
        }
    }

    /**
     * SIGINT 裁决(run 期间绑定;策略与 Signal 绑定分离,测试同步驱动本类)。规则:
     *   未静止·首次         → cancel(User):一次性任务收敛后 exit 4;REPL 取消当前轮不退出
     *   未静止·同一活动再按 → halt(130):逃生门——取消没让收敛停下(不合作工具等)时强退
     *   静止·REPL           → 请求退出(行循环轮询;同 EOF / /exit 路径)
     *   静止·一次性         → 忽略(轮已收盘,进程即将退出)
     * 「同一活动」按 whenIdle future 的身份识别:driver 每次启动换新实例(AgentLoopImpl
     * ensureDriver),实例未变且未完成 = 上次取消尚未收敛——据此把「再按一次 = 催停」与
     * 「下一轮的第一次取消」分开(否则新轮首按会误触 halt)。
     */
    static final class SigintPolicy {

        private final Supplier<CompletableFuture<Void>> whenIdle;
        private final Runnable cancelTurn;
        private final boolean repl;
        private final IntConsumer halt;
        private final Consumer<String> notice;
        private final AtomicBoolean exitRequested = new AtomicBoolean();

        /** Signal Dispatcher 单线程访问;行循环只读 {@link #exitRequested}。 */
        private CompletableFuture<Void> cancelledEpoch;

        SigintPolicy(Supplier<CompletableFuture<Void>> whenIdle, Runnable cancelTurn, boolean repl,
                     IntConsumer halt, Consumer<String> notice) {
            this.whenIdle = whenIdle;
            this.cancelTurn = cancelTurn;
            this.repl = repl;
            this.halt = halt;
            this.notice = notice;
        }

        void onSignal() {
            CompletableFuture<Void> epoch = whenIdle.get();
            if (epoch.isDone()) {
                if (repl) {
                    exitRequested.set(true);
                }
                return;
            }
            if (epoch == cancelledEpoch) {
                halt.accept(130);
                return;
            }
            cancelledEpoch = epoch;
            notice.accept("已请求取消，等待收敛（再按一次 Ctrl-C 强制退出）");
            cancelTurn.run();
        }

        /** 行循环轮询:静止期的 Ctrl-C 请求退出。 */
        boolean exitRequested() {
            return exitRequested.get();
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
            // 提示词工作目录与三处围栏同一次扇出(it21):四处同源,AppBoot 装配期断言兜底
            new ConfigRowSpec.Replace("agent-loop", Map.of("cwd", workspace.toString()), null),
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
