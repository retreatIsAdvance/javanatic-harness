package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.kernel.brand.Id;
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

import java.util.List;
import java.util.Map;

/** 测试夹具:会话事件的紧凑工厂(全部 Append 面、显式时间;断言用字面量钉死)。 */
final class TestEvents {

    private TestEvents() {
    }

    static SessionHeader header(String id) {
        return new SessionHeader(SessionHeader.FORMAT_VERSION, new Id<>(id), 0, null, 0, null);
    }

    static TurnStart turnStart(long time, int turn) {
        return new TurnStart(time, turn);
    }

    static TurnEnd turnEnd(long time, int turn, TurnEndReason reason) {
        return new TurnEnd(time, turn, reason);
    }

    static StepStart stepStart(long time, int turn, int step) {
        return new StepStart(time, turn, step);
    }

    static StepEnd stepEnd(long time, int turn, int step) {
        return new StepEnd(time, turn, step);
    }

    static UserMessageEvent user(long time, String text) {
        return new UserMessageEvent(time, UserMessage.of(text, new MessageSource.User()),
            new SurfaceOp.Append(), null);
    }

    static AssistantMessageEvent assistant(long time, int turn, int step, String text) {
        return assistant(time, turn, step, text, null);
    }

    static AssistantMessageEvent assistant(long time, int turn, int step, String text, TokenUsage usage) {
        return new AssistantMessageEvent(time, turn, step,
            AssistantMessage.of(text, new MessageSource.Model("p", "m")), usage,
            new SurfaceOp.Append(), null);
    }

    /** 空 content 列表的 assistant 事件——只承载 usage,DeriveMessage 投影为 null。 */
    static AssistantMessageEvent assistantEmpty(long time, int turn, int step, TokenUsage usage) {
        return new AssistantMessageEvent(time, turn, step,
            new AssistantMessage(new MessageSource.Model("p", "m"), List.of()), usage,
            new SurfaceOp.Append(), null);
    }

    static ToolCallEvent call(long time, int turn, int step, String callId, String name, String arguments) {
        return new ToolCallEvent(time, turn, step, CallId.of(callId), name, arguments);
    }

    static ToolResultEvent result(long time, int turn, int step, String callId, String content,
                                  boolean isError) {
        return new ToolResultEvent(time, turn, step,
            new ToolResultBlock(CallId.of(callId), content, isError), false,
            new SurfaceOp.Append(), null);
    }

    /** concludesTurn=true 的结果事件夹具(单独工厂避免参数越限)。 */
    static ToolResultEvent resultConcluding(long time, int turn, int step, String callId,
                                            String content, boolean isError) {
        return new ToolResultEvent(time, turn, step,
            new ToolResultBlock(CallId.of(callId), content, isError), true,
            new SurfaceOp.Append(), null);
    }

    static LlmRequestEvent llmRequest(long time, int turn, int step) {
        return new LlmRequestEvent(time, turn, step, "sha-system", "sha-tools", 1, 3,
            Map.of("model", "m"));
    }

    static CompactionStart compactionStart(long time, int turn) {
        return new CompactionStart(time, turn);
    }

    static CompactionSummary compactionSummary(long time, int turn, String summary,
                                               long shadowedStart, long shadowedEnd) {
        return new CompactionSummary(time, turn, summary, "p", "m", null, shadowedStart, shadowedEnd);
    }

    static CompactionEnd compactionEnd(long time, int turn, String error) {
        return new CompactionEnd(time, turn, error);
    }

    static RequestHeader requestHeader(long time, String cwd, String date) {
        return new RequestHeader(time, cwd, date);
    }

    static ProjectInstructions instructions(long time, String path, String sha256, boolean truncated,
                                            String content) {
        return new ProjectInstructions(time, path, sha256, truncated, content);
    }

    static SessionEndSeedEvent endSeed(long time) {
        return new SessionEndSeedEvent(time);
    }

    /**
     * 未知扩展事件夹具:payload 的 toString 即炸——投影层按契约只读 type(),
     * 任何触碰负载的实现在测试里当场失败。
     */
    record FakeExtension(long time, String kind, Object payload) implements ExtensionEvent {

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
