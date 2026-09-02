package io.javanatic.harness.llm;

import io.javanatic.harness.kernel.brand.Id;

/**
 * 工具调用 id 的品牌类型：跨 seam 流转的 opaque 标识，不裸传 String。
 * 配对键——模型的 tool-use 分块与 executor 的 tool/result 经它对上。
 */
public final class CallId {

    private CallId() {
    }

    /** @param raw 调用 id 原文（非空） */
    public static Id<CallId> of(String raw) {
        return new Id<>(raw);
    }
}
