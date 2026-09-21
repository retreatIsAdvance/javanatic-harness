package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.CompactionEnd;
import io.javanatic.harness.session.event.CompactionStart;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.session.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;
import java.lang.System.Logger;

/**
 * 压缩 Provider(id "compaction",requires "llm")。阈值/保留预算/独立摘要模型/
 * 重试经行配置(dsh/agentscope 对照后的形状:三事件锁事务 + tool 配对边界 +
 * 最终-user-message 摘要指令 + fail-closed)。
 */
public final class CompactionPlugin implements Plugin, CompactionService {

    private static final Logger LOG = System.getLogger(CompactionPlugin.class.getName());

    /** 数据组合路径的文档化默认:阈值比例模型相对(dsh 对照);保留是摘要侧绝对预算。 */
    public static final double DEFAULT_THRESHOLD_RATIO = 0.8;
    public static final long DEFAULT_RETAIN_TOKENS = 10_000;
    public static final int DEFAULT_RETRIES = 1;

    /** 估价比率(agentscope 校准:中英混合保守值 + 结构开销)。 */
    static final double CHARS_PER_TOKEN = 2.5;
    static final int MESSAGE_OVERHEAD = 5;
    static final int TOOL_BLOCK_OVERHEAD = 9;

    private static final String CHECKPOINT_PREAMBLE =
        "This is an automatically generated checkpoint condensing an earlier span of the conversation"
            + " to free up context. Treat the captured context as established background and build on it"
            + " without restating it. Continue the task directly from the messages that follow.";

    private static final String COMPACTION_INSTRUCTION = """
        You are now acting as a compaction engine for this AI coding assistant. Condense the \
        conversation ABOVE into a structured checkpoint that lets another model resume the work \
        with no loss of essential context.

        Output EXACTLY the Markdown structure below: keep every section, in order. Use terse \
        bullets, not prose paragraphs. Write "(none)" for an empty section — never drop a section.

        ## Primary Request and Intent
        - [the user's original and evolving goals; quote verbatim where the exact wording matters]

        ## Key Technical Concepts
        - [technologies, frameworks, patterns, and conventions in play]

        ## Files and Code
        - [exact path: why it matters, key changes or snippets]

        ## Errors and Fixes
        - [error: how it was resolved, plus any related user feedback]

        ## Pending Jobs
        - [explicitly requested work not yet completed]

        ## Current Work
        - [precisely what was in progress at this checkpoint]

        ## Next Step
        - [the single next action, directly in line with the most recent request, or "(none)"]

        ## Critical Context
        - [decisions and their rationale, constraints, user preferences, open questions, data \
        needed to continue]

        Rules:
        - Preserve exact file paths, commands, error strings, identifiers, numeric values, \
        function signatures, and syntax fragments.
        - Capture user feedback and explicit instructions faithfully, especially corrections.
        - Do NOT mention this summarization request or that the context was compacted.
        - Output only the checkpoint text: do not call any tool or take any other action.
        - If the conversation already contains a checkpoint preamble, it is a PRIOR checkpoint: \
        preserve still-true facts, drop stale ones, and merge newer information into a single \
        consolidated summary under the same structure.""";

    private final LlmService llm;
    private final long thresholdTokens;
    private final long retainTokens;
    private final int retries;
    private final String summarizationProvider;
    private final String summarizationModel;

    /** 数据组合路径:参数从行配置解析(contextWindow/thresholdRatio/maxContextTokens/retainTokens)。 */
    public CompactionPlugin() {
        this.llm = null;
        this.thresholdTokens = -1;
        this.retainTokens = -1;
        this.retries = -1;
        this.summarizationProvider = null;
        this.summarizationModel = null;
    }

    private CompactionPlugin(LlmService llm, long thresholdTokens, long retainTokens, int retries,
                             String summarizationProvider, String summarizationModel) {
        this.llm = llm;
        this.thresholdTokens = thresholdTokens;
        this.retainTokens = retainTokens;
        this.retries = retries;
        this.summarizationProvider = summarizationProvider;
        this.summarizationModel = summarizationModel;
    }

    @Override
    public String id() {
        return "compaction";
    }

    @Override
    public java.util.Set<String> requires() {
        return java.util.Set.of("llm");
    }

    @Override
    public boolean shouldCompact(Session session) {
        return shouldCompact0(session);
    }

    @Override
    public CompactionSummary compact(Session session, int turn, LlmCallConfig route,
                                      AbortSignal signal) {
        return compact(session, turn, route, signal, false);
    }

    @Override
    public CompactionSummary compactNow(Session session, int turn, LlmCallConfig route,
                                        AbortSignal signal) {
        return compact(session, turn, route, signal, true);
    }

    @Override
    public void apply(Scope scope) {
        LlmService service = scope.require(LlmService.KEY);
        Map<String, Object> config = scope.require(ConfigService.KEY).configFor(id());
        // 阈值解析(dsh 形状):绝对 maxContextTokens 覆盖 > contextWindow × thresholdRatio;
        // 皆缺 → fail loud——不知道模型窗口大小时拒绝运行压力策略,绝不猜
        long override = ConfigValues.longValue(config, id(), "maxContextTokens", 0);
        long contextWindow = ConfigValues.longValue(config, id(), "contextWindow", 0);
        long threshold = override > 0 ? override : 0;
        if (threshold == 0 && contextWindow > 0) {
            threshold = Math.round(contextWindow * ConfigValues.doubleValue(
                config, id(), "thresholdRatio", DEFAULT_THRESHOLD_RATIO));
        }
        if (threshold <= 0) {
            throw new IllegalStateException("compaction: no context capacity configured — "
                + "set contextWindow (thresholdRatio applies) or maxContextTokens; "
                + "refusing to guess the model's window size");
        }
        scope.provide(CompactionService.KEY, new CompactionPlugin(
            service,
            threshold,
            ConfigValues.longValue(config, id(), "retainTokens", DEFAULT_RETAIN_TOKENS),
            (int) ConfigValues.longValue(config, id(), "retries", DEFAULT_RETRIES),
            ConfigValues.stringValue(config, id(), "summarizationProvider", null),
            ConfigValues.stringValue(config, id(), "summarizationModel", null)));
    }

    private boolean shouldCompact0(Session session) {
        return lastInputTokens(session) > thresholdTokens;
    }

    private CompactionSummary compact(Session session, int turn, LlmCallConfig route,
                                      AbortSignal signal, boolean forced) {
        List<Long> surface = session.surfaceSeqs();
        int keepFrom = retainBoundary(session, surface);
        if (keepFrom <= 0) {
            // 无可压缩区间(tail 覆盖全部 surface):压力路径跳过——真空由请求侧溢出显形
            // (it20 前此处抛错直接判死可挽救的轮);强制路径交调用方不重试
            if (!forced) {
                LOG.log(Logger.Level.WARNING,
                    "compaction skipped: nothing to compact (tail covers surface)");
            }
            return null;
        }
        long start = surface.getFirst();
        long end = surface.get(keepFrom - 1);

        session.append(new CompactionStart(System.currentTimeMillis(), turn));
        try {
            Summarized summarized = summarize(session, surface, keepFrom, route, signal);
            session.append(new UserMessageEvent(System.currentTimeMillis(),
                new UserMessage(new MessageSource.Compaction(),
                    List.of(new TextBlock(CHECKPOINT_PREAMBLE + "\n\n" + summarized.text()))),
                new SurfaceOp.Replace(start, end), shadowedSeqs(surface, 0, keepFrom - 1)));
            CompactionSummary audit = new CompactionSummary(System.currentTimeMillis(), turn,
                summarized.text(), summarized.provider(), summarized.model(), summarized.usage(),
                start, end);
            session.append(audit);
            session.append(new CompactionEnd(System.currentTimeMillis(), turn, null));
            return audit;
        } catch (RuntimeException e) {
            session.append(new CompactionEnd(System.currentTimeMillis(), turn, e.toString()));
            throw e;
        }
    }

    /** 摘要调用:重放前缀 + 最终 user message 指令(KV cache 前缀复用);fail-closed 有界重试。 */
    private Summarized summarize(Session session, List<Long> surface, int keepFrom,
                                 LlmCallConfig route, AbortSignal signal) {
        // 前缀投影:从 surface 序列重建模型消息(project 是 session 内部,这里逐事件等价投影)
        List<Message> replay = new ArrayList<>();
        for (int i = 0; i < keepFrom; i++) {
            Object event = session.eventAt(surface.get(i));
            if (event instanceof UserMessageEvent um) {
                replay.add(um.message());
            } else if (event instanceof AssistantMessageEvent am && !am.message().content().isEmpty()) {
                replay.add(am.message());
            } else if (event instanceof ToolResultEvent tr) {
                replay.add(new UserMessage(new MessageSource.Tool(tr.block().toolUseId()),
                    List.of(tr.block())));
            }
        }
        replay.add(UserMessage.of(COMPACTION_INSTRUCTION, new MessageSource.User()));
        LlmCallConfig effective = summarizationProvider != null
            ? new LlmCallConfig(summarizationProvider, summarizationModel)
            : route;
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            signal.checkAbort();
            try (Stream<StreamChunk> chunks = llm.stream(effective,
                    new LlmRequest(null, replay, List.of(), Map.of()), signal)) {
                ChunkAssembly.Assembled assembled = ChunkAssembly.fold(chunks.toList());
                if (assembled.finishReason() == FinishReason.LENGTH) {
                    throw new IllegalStateException(
                        "compaction summary truncated at the token cap (incomplete checkpoint)");
                }
                if (assembled.text().isBlank()) {
                    throw new IllegalStateException("compaction summary empty");
                }
                return new Summarized(assembled.text(), effective.provider(), effective.model(),
                    assembled.usage());
            } catch (AbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    /**
     * 保留边界:从尾部估价累计到 retainTokens;边界落在 tool 结果上时回退
     * (盖写区间的末节点必须配对完整——assistant 的 tool_use 不能与其 tool/result 分离)。
     */
    private int retainBoundary(Session session, List<Long> surface) {
        long accumulated = 0;
        int keepFrom = surface.size();
        for (int i = surface.size() - 1; i >= 0; i--) {
            accumulated += estimateTokens(session.eventAt(surface.get(i)));
            keepFrom = i;
            if (accumulated >= retainTokens) {
                break;
            }
        }
        while (keepFrom > 0 && isToolResult(session.eventAt(surface.get(keepFrom)))) {
            keepFrom--;
        }
        return keepFrom;
    }

    private static boolean isToolResult(Object event) {
        return event instanceof ToolResultEvent;
    }

    /**
     * 末次 assistant 消息报告的 inputTokens（0=尚无观测）。it20 修正：此前取全日志
     * max()（高位水位）——一次跨阈后每个 step top 都复发压缩；末次口径与
     * {@link CompactionService#shouldCompact} 措辞及 03 §6 一致，压缩后新请求的
     * 实测值自然回落，且跨 resume 仍读最近一次真实请求。
     */
    private static long lastInputTokens(Session session) {
        var events = session.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).event() instanceof AssistantMessageEvent message
                    && message.usage() != null) {
                return message.usage().inputTokens();
            }
        }
        return 0;
    }

    /** 事件文本的估价(chars/2.5 + 结构开销;agentscope 校准,JH 无 tokenizer 的诚实口径)。 */
    static long estimateTokens(Object event) {
        if (event instanceof UserMessageEvent um) {
            long chars = um.message().content().stream()
                .map(CompactionPlugin::blockChars).mapToLong(Long::longValue).sum();
            return (long) (chars / CHARS_PER_TOKEN) + MESSAGE_OVERHEAD
                + um.message().content().stream().filter(ToolResultBlock.class::isInstance).count()
                    * TOOL_BLOCK_OVERHEAD;
        }
        if (event instanceof AssistantMessageEvent am) {
            long chars = am.message().content().stream()
                .map(CompactionPlugin::blockChars).mapToLong(Long::longValue).sum();
            return (long) (chars / CHARS_PER_TOKEN) + MESSAGE_OVERHEAD;
        }
        if (event instanceof ToolResultEvent tr) {
            return (long) (tr.block().content().length() / CHARS_PER_TOKEN)
                + MESSAGE_OVERHEAD + TOOL_BLOCK_OVERHEAD;
        }
        return 0;
    }

    private static long blockChars(Object block) {
        if (block instanceof TextBlock text) {
            return text.text().length();
        }
        if (block instanceof ToolUseBlock use) {
            return use.arguments().length() + use.name().length();
        }
        if (block instanceof ToolResultBlock result) {
            return result.content().length();
        }
        return 0;
    }

    private static List<Long> shadowedSeqs(List<Long> surface, int from, int to) {
        return surface.subList(from, to + 1);
    }

    private record Summarized(String text, String provider, String model, TokenUsage usage) {
    }
}
