package io.javanatic.harness.session;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;

import java.util.List;

/**
 * 单节点投影规则：一个 surface 事件派生为哪条 LLM 消息。null = 不产生消息。
 * 这是 deriveMessages 的每节点纯函数；外部重建器对日志前缀折叠同一函数，
 * 即可重建任意一次请求见到的消息序列（R1）。
 */
final class DeriveMessage {

    private DeriveMessage() {
    }

    static Message project(SessionEvent event) {
        return switch (event) {
            case UserMessageEvent um -> um.message(); // 逐字透传，不加框架
            case AssistantMessageEvent am ->
                am.message().content().isEmpty() ? null : am.message(); // 空消息只承载 usage
            case ToolResultEvent tr -> new UserMessage(
                new MessageSource.Tool(tr.block().toolUseId()), List.of(tr.block()));
            default -> null; // 开放联合：非 surface 事件与扩展事件不产生核心消息
        };
    }
}
