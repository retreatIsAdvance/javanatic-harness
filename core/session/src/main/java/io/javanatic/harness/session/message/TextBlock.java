package io.javanatic.harness.session.message;

import java.util.Objects;

/** 文本内容块。 */
public record TextBlock(String text) implements ContentBlock {

    /** @throws NullPointerException text 为 null 时 */
    public TextBlock {
        Objects.requireNonNull(text, "text");
    }
}
