package io.javanatic.harness.session;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEndSeedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 恢复收口：尾形分析纯函数 + closeInterrupted 追加序/配对/幂等（it19）。 */
class SessionRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(9_000), ZoneOffset.UTC);

    @Test
    void cleanTailProducesNoFacts() {
        List<LoggedEvent<? extends SessionEvent>> events = history(
            new TurnStart(1, 0),
            new StepStart(2, 0, 0),
            toolUseMessage(3, 0, 0, "c1"),
            new ToolCallEvent(4, 0, 0, CallId.of("c1"), "fs_read", "{}"),
            new ToolResultEvent(5, 0, 0,
                new ToolResultBlock(CallId.of("c1"), "ok", false), false,
                new SurfaceOp.Append(), null),
            new StepEnd(6, 0, 0),
            new TurnEnd(7, 0, new TurnEndReason.Completed()));
        assertThat(SessionRecovery.analyze(events)).isEmpty();
    }

    @Test
    void messageLevelResultResolvesToolUseEvenWithoutAuditCall() {
        // 配对口径 = 消息级 tool_use ∪ 审计级 tool/call − 已配对 tool/result：
        // 缺审计 tool/call（崩溃于批量前导前）时，result 已够解悬空
        List<LoggedEvent<? extends SessionEvent>> events = history(
            new TurnStart(1, 0),
            new StepStart(2, 0, 0),
            toolUseMessage(3, 0, 0, "c1"),
            new ToolResultEvent(4, 0, 0,
                new ToolResultBlock(CallId.of("c1"), "ok", false), false,
                new SurfaceOp.Append(), null),
            new StepEnd(5, 0, 0),
            new TurnEnd(6, 0, new TurnEndReason.Completed()));
        assertThat(SessionRecovery.analyze(events)).isEmpty();
    }

    @Test
    void danglingAuditCallWithoutAssistantMessageLocatedAtCallSeq() {
        List<LoggedEvent<? extends SessionEvent>> events = history(
            new TurnStart(1, 0),
            new StepStart(2, 0, 0),
            new ToolCallEvent(3, 0, 0, CallId.of("c2"), "fs_read", "{}"),
            new StepEnd(4, 0, 0),
            new TurnEnd(5, 0, new TurnEndReason.Completed()));
        // 审计级 tool/call 位于 seq 2（历史按索引分配 seq）；turn 已闭合 → 无收口事实
        assertThat(SessionRecovery.analyze(events))
            .containsExactly(new SessionRecovery.Fact.ResultUnknown(0, 0, CallId.of("c2"), 2));
    }

    @Test
    void openTailYieldsCallFactsThenStepCloseThenTurnClose() {
        List<LoggedEvent<? extends SessionEvent>> events = history(
            new TurnStart(1, 0),
            new StepStart(2, 0, 0),
            toolUseMessage(3, 0, 0, "c1"),
            new ToolCallEvent(4, 0, 0, CallId.of("c1"), "fs_read", "{}"));
        // 消息级与审计级同 id 都悬空：sourceSeq 取首现（assistant 消息 seq 2）
        assertThat(SessionRecovery.analyze(events)).containsExactly(
            new SessionRecovery.Fact.ResultUnknown(0, 0, CallId.of("c1"), 2),
            new SessionRecovery.Fact.StepClose(0, 0),
            new SessionRecovery.Fact.TurnClose(0));
    }

    @Test
    void closeInterruptedAppendsFactsAfterEndSeedAndIsIdempotent() {
        List<SessionEvent> seed = List.of(
            new TurnStart(1, 0),
            new StepStart(2, 0, 0),
            toolUseMessage(3, 0, 0, "c1"),
            new ToolCallEvent(4, 0, 0, CallId.of("c1"), "fs_read", "{}"));
        Session session = Session.create(Session.newId("s1"), seed, null);

        assertThat(SessionRecovery.closeInterrupted(session, CLOCK)).isEqualTo(3);

        List<LoggedEvent<? extends SessionEvent>> events = session.events();
        assertThat(events).hasSize(seed.size() + 1 + 3);
        assertThat(events.get(seed.size()).event()).isInstanceOf(SessionEndSeedEvent.class);

        ToolResultEvent recovered = (ToolResultEvent) events.get(seed.size() + 1).event();
        assertThat(recovered.block().content()).isEqualTo(SessionRecovery.RESULT_UNKNOWN_TEXT);
        assertThat(recovered.block().isError()).isTrue();
        assertThat(recovered.block().toolUseId()).isEqualTo(CallId.of("c1"));
        assertThat(recovered.concludesTurn()).isFalse();
        assertThat(recovered.time()).isEqualTo(9_000);
        // sourceEventSeqs 引 seed 前的悬空消息 seq（seed 按索引分配：消息在 seq 2）
        assertThat(recovered.sourceEventSeqs()).containsExactly(2L);
        assertThat(recovered.sourceEventSeqs().getFirst())
            .isLessThan((long) seed.size() + 1);

        // StepEnd 必须先于 TurnEnd（invariant：turn/end 不得落在开着的 step 内）
        assertThat(events.get(seed.size() + 2).event()).isEqualTo(new StepEnd(9_000, 0, 0));
        assertThat(events.get(seed.size() + 3).event())
            .isEqualTo(new TurnEnd(9_000, 0, new TurnEndReason.Aborted(SessionRecovery.INTERRUPTED)));

        SessionInvariants.validate(events);
        // 配对闭合：投影里出现 c1 的 tool 结果消息（悬空 tool_use 不再存在）
        assertThat(session.deriveMessages().stream().flatMap(message -> message.content().stream()))
            .anyMatch(block -> block instanceof ToolResultBlock tool
                && tool.toolUseId().equals(CallId.of("c1")));

        assertThat(SessionRecovery.closeInterrupted(session, CLOCK)).isZero();
        assertThat(session.events()).hasSize(seed.size() + 1 + 3);
    }

    private static AssistantMessageEvent toolUseMessage(long time, int turn, int step, String callId) {
        return new AssistantMessageEvent(time, turn, step,
            new AssistantMessage(new MessageSource.Model("replay", "m"),
                List.of(new TextBlock("先看文件"), new ToolUseBlock(CallId.of(callId), "fs_read", "{}"))),
            null, new SurfaceOp.Append(), null);
    }

    private static List<LoggedEvent<? extends SessionEvent>> history(SessionEvent... events) {
        List<LoggedEvent<? extends SessionEvent>> log = new ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            log.add(new LoggedEvent<>(i, events[i]));
        }
        return log;
    }
}
