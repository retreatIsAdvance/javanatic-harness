package io.javanatic.harness.session.message;

/**
 * 消息来源。开放接口（工具来源随 tools 切片加入），变体以嵌套 record 提供：
 * {@link User}（人类输入或注入上下文）、{@link Model}（模型输出）。
 */
public interface MessageSource {

    /** 人类输入。 */
    record User() implements MessageSource {}

    /** 模型输出。 */
    record Model(String provider, String model) implements MessageSource {}
}
