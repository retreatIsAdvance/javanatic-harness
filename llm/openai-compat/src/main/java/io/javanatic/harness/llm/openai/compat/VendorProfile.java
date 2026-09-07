package io.javanatic.harness.llm.openai.compat;

import java.util.Map;
import java.util.Objects;

/**
 * OpenAI 兼容厂商的差异画像——接入一家兼容厂商只需一个 profile,不再复制适配器。
 * 兼容协议的解码差异(如 DeepSeek 合并结束分块 vs OpenAI 分离形状)由通用解码器
 * 容错取并集,不进 profile(05 实现落定)。
 *
 * @param baseUrl      API 根(如 https://api.deepseek.com)
 * @param endpointPath 聊天端点路径(默认 /chat/completions;OpenAI Responses API 等真差异才改)
 * @param extraHeaders 附加请求头(如 OpenAI 组织头)
 */
public record VendorProfile(String baseUrl, String endpointPath, Map<String, String> extraHeaders) {

    /** @throws NullPointerException/IllegalArgumentException 任一字段非法时 */
    public VendorProfile {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must be non-empty");
        }
        endpointPath = endpointPath == null || endpointPath.isEmpty()
            ? "/chat/completions" : endpointPath;
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    /** @return 指定根与默认端点的画像 */
    public static VendorProfile of(String baseUrl) {
        return new VendorProfile(baseUrl, null, null);
    }
}
