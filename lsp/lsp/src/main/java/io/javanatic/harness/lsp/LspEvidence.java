package io.javanatic.harness.lsp;

import java.util.Objects;
import java.util.OptionalInt;

/** 批次审计参考：receiveSequence 是本地完整消息截点；version 不是来源证明。 */
public record LspEvidence(Origin origin, long receiveSequence, OptionalInt serverVersion) {
    public LspEvidence {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(serverVersion, "serverVersion");
        if (receiveSequence < 0) {
            throw new IllegalArgumentException("receiveSequence must be nonnegative");
        }
        if (origin != Origin.PUBLISHED_DIAGNOSTICS && serverVersion.isPresent()) {
            throw new IllegalArgumentException("only a diagnostics publication can carry serverVersion");
        }
    }

    /** 无通知与明确空数组必须可辨；请求响应不能由通知沉默合成。 */
    public enum Origin {
        QUIET_WINDOW, PUBLISHED_DIAGNOSTICS, REQUEST_RESPONSE
    }
}
