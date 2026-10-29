package io.javanatic.harness.lsp;

import java.net.URI;
import java.time.Duration;

/**
 * 单文档观察请求。timeout 从调用入口计，包含同步、窗口与最终采样，不逐步重置。
 * observationWindow 自本地同步帧完整写出后计；URI 形状校验不是权限许可。
 */
public record LspQuery(URI document, Duration timeout, Duration observationWindow) {
    public LspQuery {
        LspValues.uri(document);
        LspValues.positive(timeout);
        LspValues.positive(observationWindow);
        if (observationWindow.compareTo(timeout) > 0) {
            throw new IllegalArgumentException("observationWindow exceeds total timeout");
        }
    }
}
