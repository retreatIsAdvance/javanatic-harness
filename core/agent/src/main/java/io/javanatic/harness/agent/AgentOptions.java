package io.javanatic.harness.agent;

import java.util.Objects;

/**
 * agent 的路由身份：默认 LLM 调用配置由它派生（agent/request waterfall 可改写）。
 *
 * @param provider 已注册的 LLM adapter 名（如 "replay"、"deepseek"）
 * @param model    provider 侧的模型 id
 */
public record AgentOptions(String provider, String model) {

    /** @throws NullPointerException/IllegalArgumentException provider/model 为 null 或空时 */
    public AgentOptions {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        if (provider.isEmpty() || model.isEmpty()) {
            throw new IllegalArgumentException("provider and model must be non-empty");
        }
    }
}
