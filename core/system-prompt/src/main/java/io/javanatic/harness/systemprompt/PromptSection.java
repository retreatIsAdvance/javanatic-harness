package io.javanatic.harness.systemprompt;

import java.util.Objects;

/**
 * 提示词的一个贡献段。
 *
 * @param priority 排序键（小者在前；相等时保持注册序——确定性拼接，R1）
 * @param content  段文本（非空）
 */
public record PromptSection(int priority, String content) {

    /** @throws NullPointerException content 为 null 时 */
    public PromptSection {
        Objects.requireNonNull(content, "content");
    }
}
