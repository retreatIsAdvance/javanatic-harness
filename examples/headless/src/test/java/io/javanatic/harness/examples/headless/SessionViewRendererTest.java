package io.javanatic.harness.examples.headless;

import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionHeader;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.CompactionEnd;
import io.javanatic.harness.session.event.CompactionStart;
import io.javanatic.harness.session.event.CompactionSummary;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LlmRequestEvent;
import io.javanatic.harness.session.event.ProjectInstructions;
import io.javanatic.harness.session.event.RequestHeader;
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
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.persistence.SessionPersistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * it33 停 2:--session 检视的纯文本渲染面。渲染只读投影 record(不做事实解析);
 * 行式输出契约逐行钉死——未知事件按 type 渲染、负载不进输出(投影层边界声明),
 * 渲染层沿用同一纪律(未知事件不触负载,夹具 bomb 即证)。
 */
class SessionViewRendererTest {

    @Test
    void turnsViewRendersOneLinePerTurnWithDurationsAndTokens() {
        List<SessionEvent> events = List.of(
            turnStart(100, 1),
            stepStart(110, 1, 0),
            assistant(120, 1, 0, "hi", usage(10, 20, 1)),
            call(130, 1, 0, "c1", "fs_write", "{}"),
            turnEnd(140, 1, new TurnEndReason.Completed()),
            turnStart(200, 2),
            stepStart(210, 2, 0),
            turnEnd(220, 2, new TurnEndReason.Aborted("user")),
            turnStart(300, 3));
        assertThat(SessionViewRenderer.render(loaded("sv", events), "turns"))
            .containsExactly(
                "session=sv  view=turns  events=9",
                "turn=1  completed  duration=40ms  steps=1  tools=1  tokens_in=10  tokens_out=20  tokens_reason=1",
                "turn=2  aborted:user  duration=20ms  steps=1  tools=0  tokens=none",
                "turn=3  open  steps=0  tools=0  tokens=none");
    }

    @Test
    void toolsViewPairsByCallIdAndRendersOrphansAndEscaping() {
        List<SessionEvent> events = List.of(
            call(10, 1, 0, "c1", "fs_read", "{\"p\":\"/x\"}"),
            result(20, 1, 0, "c1", "ok", false),
            call(30, 1, 0, "c2", "web_fetch", "{\"u\":\"a\nb\"}"),
            result(40, 2, 0, "c3", "boom", true));
        assertThat(SessionViewRenderer.render(loaded("sv", events), "tools"))
            .containsExactly(
                "session=sv  view=tools  events=4",
                "turn=1 step=0  call seq=0 fs_read  args={\"p\":\"/x\"}  ->  seq=1  ok  10ms",
                "turn=1 step=0  call seq=2 web_fetch  args={\"u\":\"a\\nb\"}  ->  无结果",
                "turn=2 step=0  orphan seq=3  error  (无对应调用)");
    }

    @Test
    void usageViewRendersTotalAndPerTurnIncludingUnreported() {
        List<SessionEvent> events = List.of(
            turnStart(100, 1),
            assistant(110, 1, 0, "a", usage(5, 6, 1)),
            assistant(120, 1, 0, "b", usage(7, 8, 2)),
            turnEnd(130, 1, new TurnEndReason.Completed()),
            turnStart(200, 2),
            assistant(210, 2, 0, "c"));
        assertThat(SessionViewRenderer.render(loaded("sv", events), "usage"))
            .containsExactly(
                "session=sv  view=usage  events=6",
                "total  tokens_in=12  tokens_out=14  tokens_reason=3",
                "turn=1  tokens_in=12  tokens_out=14  tokens_reason=3",
                "turn=2  (未报告)");
    }

    @Test
    void usageViewRendersNoMeteringWhenNothingReported() {
        List<SessionEvent> events = List.of(
            turnStart(100, 1),
            turnEnd(200, 1, new TurnEndReason.Completed()));
        assertThat(SessionViewRenderer.render(loaded("sv", events), "usage"))
            .containsExactly("session=sv  view=usage  events=2", "total  (无计量)");
    }

    @Test
    void timelineViewRendersAllCoreVariantsAndUnknownByTypeOnly() {
        List<SessionEvent> events = List.of(
            turnStart(0, 1),
            stepStart(10, 1, 0),
            user(20, "hello"),
            assistant(30, 1, 0, "hi", usage(1, 2, 0)),
            llmRequest(40, 1, 0, Map.of("b", "2", "a", "1")),
            call(50, 1, 0, "c1", "fs_write", "{}"),
            result(60, 1, 0, "c1", "ok", false),
            stepEnd(70, 1, 0),
            turnEnd(80, 1, new TurnEndReason.Completed()),
            compactionStart(90, 2),
            compactionSummary(100, 2, "sum", 1, 4, usage(3, 4, 0)),
            compactionEnd(110, 2, null),
            requestHeader(120, "/w", "2026-09-22"),
            instructions(130, "p.md", "abc123", true, "content"),
            endSeed(140),
            new FakeExtension(150, "bomb", bomb()));
        assertThat(SessionViewRenderer.render(loaded("sv", events), "timeline"))
            .containsExactly(
                "session=sv  view=timeline  events=16",
                "0  0  turn/start  turn=1",
                "1  10  step/start  turn=1 step=0",
                "2  20  user/message  source=User chars=5",
                "3  30  assistant/message  turn=1 step=0 chars=2 usage=in=1 out=2 reason=0",
                "4  40  llm/request  turn=1 step=0 from=1 to=3 params=a=1,b=2",
                "5  50  tool/call  turn=1 step=0 name=fs_write args={}",
                "6  60  tool/result  turn=1 step=0 ok chars=2 concludes=false",
                "7  70  step/end  turn=1 step=0",
                "8  80  turn/end  turn=1 reason=completed",
                "9  90  compaction/start  turn=2",
                "10  100  compaction/summary  turn=2 chars=3 shadowed=1..4 usage=in=3 out=4 reason=0",
                "11  110  compaction/end  turn=2",
                "12  120  request/header  cwd=/w date=2026-09-22",
                "13  130  project/instructions  path=p.md sha256=abc123 truncated=true chars=7",
                "14  140  session/end-seed",
                "15  150  fake/bomb");
    }

    @Test
    void messagesViewRendersDerivedMessagesWithToolBlocks() {
        List<SessionEvent> events = List.of(
            user(0, "line one\nline two"),
            assistant(10, 1, 0, "answer"),
            result(20, 1, 0, "c1", "done", false));
        assertThat(SessionViewRenderer.render(loaded("sv", events), "messages"))
            .containsExactly(
                "session=sv  view=messages  events=3",
                "[1]  User  line one\\nline two",
                "[2]  Model  answer",
                "[3]  Tool(c1)  <tool-result c1 ok> done");
    }

    /** 空日志:除 usage(恒有 total 行)外每视图只出头行——B34 防御面(投影 isEmpty
     *  短路)经渲染入口验证。 */
    @Test
    void emptySessionRendersHeaderOnlyInEveryView() {
        SessionPersistence.Loaded loaded = loaded("sv", List.of());
        for (String view : List.of("turns", "tools", "timeline", "messages")) {
            assertThat(SessionViewRenderer.render(loaded, view))
                .containsExactly("session=sv  view=" + view + "  events=0");
        }
        assertThat(SessionViewRenderer.render(loaded, "usage"))
            .containsExactly("session=sv  view=usage  events=0", "total  (无计量)");
    }

    @Test
    void unknownViewFailsLoud() {
        assertThatThrownBy(() -> SessionViewRenderer.render(loaded("sv", List.of()), "wat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wat");
    }

    // ────────── 夹具:紧凑工厂(构造面与 projection 测试 TestEvents 同源) ──────────

    private static SessionPersistence.Loaded loaded(String id, List<SessionEvent> events) {
        return new SessionPersistence.Loaded(
            new SessionHeader(SessionHeader.FORMAT_VERSION, Session.newId(id), 0, null, 0, null),
            events);
    }

    private static TokenUsage usage(long in, long out, long reason) {
        return new TokenUsage(in, out, reason);
    }

    private static TurnStart turnStart(long time, int turn) {
        return new TurnStart(time, turn);
    }

    private static TurnEnd turnEnd(long time, int turn, TurnEndReason reason) {
        return new TurnEnd(time, turn, reason);
    }

    private static StepStart stepStart(long time, int turn, int step) {
        return new StepStart(time, turn, step);
    }

    private static StepEnd stepEnd(long time, int turn, int step) {
        return new StepEnd(time, turn, step);
    }

    private static UserMessageEvent user(long time, String text) {
        return new UserMessageEvent(time, UserMessage.of(text, new MessageSource.User()),
            new SurfaceOp.Append(), null);
    }

    private static AssistantMessageEvent assistant(long time, int turn, int step, String text) {
        return assistant(time, turn, step, text, null);
    }

    private static AssistantMessageEvent assistant(long time, int turn, int step, String text,
                                                   TokenUsage usage) {
        return new AssistantMessageEvent(time, turn, step,
            AssistantMessage.of(text, new MessageSource.Model("p", "m")), usage,
            new SurfaceOp.Append(), null);
    }

    private static ToolCallEvent call(long time, int turn, int step, String callId, String name,
                                      String arguments) {
        return new ToolCallEvent(time, turn, step, CallId.of(callId), name, arguments);
    }

    private static ToolResultEvent result(long time, int turn, int step, String callId,
                                          String content, boolean isError) {
        return new ToolResultEvent(time, turn, step,
            new ToolResultBlock(CallId.of(callId), content, isError), false,
            new SurfaceOp.Append(), null);
    }

    private static LlmRequestEvent llmRequest(long time, int turn, int step,
                                              Map<String, String> params) {
        return new LlmRequestEvent(time, turn, step, "sha-system", "sha-tools", 1, 3, params);
    }

    private static CompactionStart compactionStart(long time, int turn) {
        return new CompactionStart(time, turn);
    }

    private static CompactionSummary compactionSummary(long time, int turn, String summary,
                                                       long shadowedStart, long shadowedEnd,
                                                       TokenUsage usage) {
        return new CompactionSummary(time, turn, summary, "p", "m", usage,
            shadowedStart, shadowedEnd);
    }

    private static CompactionEnd compactionEnd(long time, int turn, String error) {
        return new CompactionEnd(time, turn, error);
    }

    private static RequestHeader requestHeader(long time, String cwd, String date) {
        return new RequestHeader(time, cwd, date);
    }

    private static ProjectInstructions instructions(long time, String path, String sha256,
                                                    boolean truncated, String content) {
        return new ProjectInstructions(time, path, sha256, truncated, content);
    }

    private static SessionEndSeedEvent endSeed(long time) {
        return new SessionEndSeedEvent(time);
    }

    /** toString 即炸的负载:任何触碰未知事件负载的渲染实现当场失败。 */
    private static Object bomb() {
        return new Object() {
            @Override
            public String toString() {
                throw new AssertionError("未知事件负载被触碰");
            }
        };
    }

    /** 未知扩展事件夹具:投影层按契约只读 type(),渲染层同纪律。 */
    private record FakeExtension(long time, String kind, Object payload) implements ExtensionEvent {

        @Override
        public String type() {
            return "fake/" + kind;
        }

        @Override
        public boolean ignorable() {
            return true;
        }
    }
}
