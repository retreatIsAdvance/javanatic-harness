package io.javanatic.harness.session.message;

import java.util.List;
import java.util.Objects;

/** 助手消息（一步的组装后输出）。空 content 合法：只承载 usage，投影为 null。 */
public record AssistantMessage(MessageSource source, List<ContentBlock> content) implements Message {

    /** 冻结 content 并拒绝 null source（不可变性由构造时归一实现，见 Message Javadoc）。 */
    public AssistantMessage {
        Objects.requireNonNull(source, "source");
        content = List.copyOf(content);
    }

    /** 便捷构造：单文本块。 */
    public static AssistantMessage of(String text, MessageSource source) {
        return new AssistantMessage(source, List.of(new TextBlock(text)));
    }
}
