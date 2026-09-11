package io.javanatic.harness.systemprompt;

import io.javanatic.harness.session.Session;

import java.util.Objects;
import java.util.function.Function;

/**
 * 提示词的一个贡献段。静态段文本固定；动态段按会话日志派生（如 plan:policy
 * 仅在规划模式激活时出现——派生函数只读日志，同日志必同提示词，R1）。
 *
 * @param priority 排序键（小者在前；相等时保持注册序——确定性拼接，R1）
 */
public sealed interface PromptSection {

    /** 排序键（小者在前；相等时保持注册序）。 */
    int priority();

    /** @throws NullPointerException content 为 null 时 */
    record Static(int priority, String content) implements PromptSection {
        public Static {
            Objects.requireNonNull(content, "content");
        }
    }

    /** @throws NullPointerException content 为 null 时 */
    record Dynamic(int priority, Function<Session, String> content) implements PromptSection {
        public Dynamic {
            Objects.requireNonNull(content, "content");
        }
    }
}
