package io.javanatic.harness.web;

import java.util.Objects;

/**
 * 抓取正文的封闭联合：Provider 已按 Content-Type 分类并解码，消费者穷尽
 * switch；新增 kind 会让所有消费者编译失败，不允许静默透传。
 */
public sealed interface WebFetchBody {

    /** @return 解码后的文本 */
    String content();

    /** HTML/XHTML 正文。 */
    record Html(String content) implements WebFetchBody {

        /** @throws NullPointerException content 为 null 时 */
        public Html {
            Objects.requireNonNull(content, "content");
        }
    }

    /** 纯文本 / JSON / XML 等文本正文。 */
    record Text(String content) implements WebFetchBody {

        /** @throws NullPointerException content 为 null 时 */
        public Text {
            Objects.requireNonNull(content, "content");
        }
    }
}
