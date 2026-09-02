package io.javanatic.harness.llm;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.stream.Stream;

/**
 * LLM 能力入口：一个 Definition 常驻，多 Provider 经
 * {@link #registerAdapter(String, LlmAdapter)} 挂适配器；消费者按
 * {@link LlmCallConfig#provider()} 路由。消费者不 import 任何 Provider
 * （JPMS 强制，05 §3）。
 */
public interface LlmService {

    /** 本服务的服务键。 */
    ServiceKey<LlmService> KEY = new ServiceKey<>("llm");

    /**
     * 流式调用模型（按 config.provider() 路由到已注册 adapter）。
     *
     * @throws IllegalStateException 该 provider 未注册（消息含已注册清单）
     */
    Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal);

    /**
     * 注册一个 provider adapter（provider 插件在 apply 里调用）。
     *
     * @throws IllegalStateException 同名 provider 已注册
     * @return 注销凭据（provider 插件挂到自己的 scope 上，R3）
     */
    Disposable registerAdapter(String provider, LlmAdapter adapter);
}
