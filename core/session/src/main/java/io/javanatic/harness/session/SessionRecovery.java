package io.javanatic.harness.session;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 恢复收口(it19):resume 装载后识别未完成尾形(悬空 tool_use / 开着的 turn),
 * 以「恢复事实」闭合日志——**不自动重放**任何工具调用:
 *
 * <ul>
 *   <li>悬空工具调用(消息级 tool_use ∪ 审计级 tool/call,减去已配对
 *       tool/result)→ 追加 error {@code tool/result},文案
 *       {@link #RESULT_UNKNOWN_TEXT}(结果未知,可自行核验)——不闭合会让
 *       resume 后首个真实请求违反 OpenAI 配对契约(tool_use 无配对 result)</li>
 *   <li>开着的 turn → 先关开着的 step({@code step/end}),再追加
 *       {@code turn/end(Aborted("interrupted"))}</li>
 * </ul>
 *
 * 事件序契约:恢复事实在 end-seed **之后**追加(即本生命周期 append,seq 递增),
 * 其 {@code sourceEventSeqs} 引用 seed 前的悬空调用/消息 seq。分析是纯函数、
 * 收口幂等——收口过的尾形再次分析为空,不重复追加。
 */
public final class SessionRecovery {

    /** 悬空调用的恢复结果文案(模型可见文本,钉进测试)。 */
    public static final String RESULT_UNKNOWN_TEXT = "结果未知，可自行核验";

    /** 中断收口的 turn/end 原因词。 */
    public static final String INTERRUPTED = "interrupted";

    private static final System.Logger LOG = System.getLogger(SessionRecovery.class.getName());

    private SessionRecovery() {
    }

    /** 恢复事实:分析产物(纯数据,时间戳由收口方盖章)。 */
    public sealed interface Fact {

        /** 悬空工具调用的答复:error tool/result,sourceSeq = 悬空调用/消息的 seq。 */
        record ResultUnknown(int turn, int step, Id<CallId> callId, long sourceSeq) implements Fact {
        }

        /** 关闭开着的 step。 */
        record StepClose(int turn, int step) implements Fact {
        }

        /** 关闭开着的 turn(原因 = interrupted)。 */
        record TurnClose(int turn) implements Fact {
        }
    }

    /**
     * 分析未完成尾形(纯函数,不改日志)。
     *
     * @param events 会话日志快照(sourceEventSeqs 语义用其 seq)
     * @return 按追加序排列的恢复事实;尾形已闭合时为空列表
     */
    public static List<Fact> analyze(List<LoggedEvent<? extends SessionEvent>> events) {
        Map<Id<CallId>, Pending> pending = new LinkedHashMap<>();
        Set<Id<CallId>> resolved = new HashSet<>();
        int openTurn = -1;
        int openStep = -1;
        for (LoggedEvent<? extends SessionEvent> entry : events) {
            switch (entry.event()) {
                case AssistantMessageEvent message -> {
                    for (ContentBlock block : message.message().content()) {
                        if (block instanceof ToolUseBlock toolUse) {
                            pending.putIfAbsent(toolUse.id(),
                                new Pending(message.turn(), message.step(), entry.seq()));
                        }
                    }
                }
                case ToolCallEvent call -> pending.putIfAbsent(call.callId(),
                    new Pending(call.turn(), call.step(), entry.seq()));
                case ToolResultEvent result -> resolved.add(result.block().toolUseId());
                case TurnStart start -> {
                    openTurn = start.turn();
                    openStep = -1;
                }
                case StepStart start -> openStep = start.step();
                case StepEnd ignored -> openStep = -1;
                case TurnEnd ignored -> {
                    openTurn = -1;
                    openStep = -1;
                }
                default -> {
                }
            }
        }
        List<Fact> facts = new ArrayList<>();
        for (Map.Entry<Id<CallId>, Pending> entry : pending.entrySet()) {
            if (!resolved.contains(entry.getKey())) {
                Pending call = entry.getValue();
                facts.add(new Fact.ResultUnknown(call.turn(), call.step(), entry.getKey(),
                    call.sourceSeq()));
            }
        }
        if (openTurn >= 0) {
            if (openStep >= 0) {
                facts.add(new Fact.StepClose(openTurn, openStep));
            }
            facts.add(new Fact.TurnClose(openTurn));
        }
        return List.copyOf(facts);
    }

    /**
     * 执行恢复收口:分析并追加恢复事实(经 {@link Session#append},观察者照常
     * 落盘)。幂等;尾形已闭合时不动作。
     *
     * @param session resume 装载出的会话(end-seed 已在日志尾)
     * @param clock 恢复事实的时间来源(测试注入冻结钟)
     * @return 追加的事实数(0 = 尾形本已闭合)
     */
    public static int closeInterrupted(Session session, Clock clock) {
        List<Fact> facts = analyze(session.events());
        for (Fact fact : facts) {
            switch (fact) {
                case Fact.ResultUnknown result -> session.append(new ToolResultEvent(clock.millis(),
                    result.turn(), result.step(),
                    new ToolResultBlock(result.callId(), RESULT_UNKNOWN_TEXT, true),
                    false, new SurfaceOp.Append(), List.of(result.sourceSeq())));
                case Fact.StepClose step ->
                    session.append(new StepEnd(clock.millis(), step.turn(), step.step()));
                case Fact.TurnClose turn -> session.append(new TurnEnd(clock.millis(), turn.turn(),
                    new TurnEndReason.Aborted(INTERRUPTED)));
            }
        }
        if (!facts.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                "session {0}: appended {1} recovery fact(s) for interrupted tail",
                session.id().value(), facts.size());
        }
        return facts.size();
    }

    /** 悬空调用在日志中的定位(turn/step + 首现 seq)。 */
    private record Pending(int turn, int step, long sourceSeq) {
    }
}
