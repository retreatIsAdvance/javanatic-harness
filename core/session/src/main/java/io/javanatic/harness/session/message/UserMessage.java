package io.javanatic.harness.session.message;

import java.util.List;
import java.util.Objects;

/** 用户侧消息（人类输入 / 注入上下文 / 未来的工具结果）。 */
public record UserMessage(MessageSource source, List<ContentBlock> content) implements Message {

    /** 冻结 content 并拒绝 null source（不可变性由构造时归一实现，见 Message Javadoc）。 */
    public UserMessage {
        Objects.requireNonNull(source, "source");
        content = List.copyOf(content);
    }

    /** 便捷构造：单文本块。 */
    public static UserMessage of(String text, MessageSource source) {
        return new UserMessage(source, List.of(new TextBlock(text)));
    }
}
