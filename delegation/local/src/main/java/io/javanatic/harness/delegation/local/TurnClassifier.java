package io.javanatic.harness.delegation.local;

import io.javanatic.harness.delegation.DelegationFailureReason;
import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.message.ContentBlock;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;
import java.time.Instant;
import java.util.List;

/**
 * 四路终局映射纯函数（停 2 评审重点①——问态不漏的关键）：Completed + 该轮存在
 * {@code concludesTurn} 的 tool/result → NeedsParentInput（问题逐字）；
 * Completed 无停轮结果 → Succeeded（末条 assistant 文本）；Error → Failed；
 * Aborted + cancelling → Cancelled（父取消），Aborted 非取消 → Failed(EXECUTION_ERROR)。
 * 无 TurnEnd（如预算在轮内拦截）由调用方按 BUDGET_EXHAUSTED 前置处置。
 */
final class TurnClassifier {

    private TurnClassifier() {
    }

    /** @param events 子会话事件（live fold 面） @param startedAt 启动时刻 @param cancelling 是否父已发起取消 */
    static DelegationState classify(List<LoggedEvent<? extends SessionEvent>> events, Instant startedAt,
                                    boolean cancelling) {
        TurnEnd last = null;
        for (LoggedEvent<? extends SessionEvent> entry : events) {
            if (entry.event() instanceof TurnEnd end) {
                last = end;
            }
        }
        if (last == null) {
            return new DelegationState.Running(startedAt);
        }
        Instant finishedAt = Instant.now();
        if (last.reason() instanceof TurnEndReason.Completed) {
            String question = null;
            for (LoggedEvent<? extends SessionEvent> entry : events) {
                if (entry.event() instanceof ToolResultEvent result
                        && result.turn() == last.turn() && result.concludesTurn()) {
                    question = result.block().content();
                }
            }
            return question != null
                    ? new DelegationState.NeedsParentInput(question, startedAt, finishedAt)
                    : new DelegationState.Succeeded(finalAnswerText(events), startedAt, finishedAt);
        }
        if (last.reason() instanceof TurnEndReason.Error) {
            return new DelegationState.Failed(DelegationFailureReason.MODEL_ERROR, startedAt, finishedAt);
        }
        return cancelling
                ? new DelegationState.Cancelled(startedAt, finishedAt)
                : new DelegationState.Failed(DelegationFailureReason.EXECUTION_ERROR, startedAt, finishedAt);
    }

    /** 末条 assistant 消息的文本块拼接（HeadlessMain.finalAnswerText 同配方）。 */
    static String finalAnswerText(List<LoggedEvent<? extends SessionEvent>> events) {
        String text = "";
        for (LoggedEvent<? extends SessionEvent> entry : events) {
            if (entry.event() instanceof AssistantMessageEvent messageEvent) {
                text = messageText(messageEvent);
            }
        }
        return text.isBlank() ? "（子代完成但无文本结果）" : text;
    }

    private static String messageText(AssistantMessageEvent event) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : event.message().content()) {
            if (block instanceof TextBlock textBlock && text.length() > 0) {
                text.append('\n');
            }
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.text());
            }
        }
        return text.toString();
    }
}
