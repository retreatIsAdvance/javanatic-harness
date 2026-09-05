package io.javanatic.harness.agentloop;

import io.javanatic.harness.session.message.UserMessage;

import java.util.List;
import java.util.Objects;

/** pre-step 裁决（waterfall 链返回值；default=Enter(原批)）。 */
public sealed interface PreStepDecision {

    /**
     * 放行（可改写消息批——改写后的批落账为 user/message）。
     *
     * @param messages admitted 消息批
     */
    record Enter(List<UserMessage> messages) implements PreStepDecision {

        /** @throws NullPointerException messages 为 null 时 */
        public Enter {
            Objects.requireNonNull(messages, "messages");
            messages = List.copyOf(messages);
        }
    }

    /** 拒绝：本轮不开 step、不落 user/message，turn 立即以 completed 关闭。 */
    record Reject(String reason) implements PreStepDecision {
    }
}
