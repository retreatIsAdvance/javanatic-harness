package io.javanatic.harness.llm;

import java.util.Objects;

/**
 * 一次模型调用的配置。本迭代只含路由身份；采样参数（temperature、
 * reasoning effort、max tokens 等）随 deepseek 切片扩展为归一词表，
 * 厂商特有项经 {@link LlmRequest#params} 透传。
 *
 * @param provider 已注册的 adapter 名（如 "deepseek"、"replay"）
 * @param model    provider 侧的模型 id
 */
public record LlmCallConfig(String provider, String model) {

    /** @throws NullPointerException/IllegalArgumentException provider/model 为 null 或空时 */
    public LlmCallConfig {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        if (provider.isEmpty() || model.isEmpty()) {
            throw new IllegalArgumentException("provider and model must be non-empty");
        }
    }
}
