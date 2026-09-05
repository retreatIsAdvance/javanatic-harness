package io.javanatic.harness.agent;

/**
 * 取消选项。
 *
 * @param keepInbox true 保留 pending 输入（resume 场景）；false 清空（默认）
 */
public record CancelOptions(boolean keepInbox) {

    /** 默认：清空 pending 输入。 */
    public static final CancelOptions DEFAULT = new CancelOptions(false);
}
