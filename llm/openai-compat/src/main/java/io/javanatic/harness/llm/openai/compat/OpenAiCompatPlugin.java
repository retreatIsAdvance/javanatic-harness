package io.javanatic.harness.llm.openai.compat;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.LlmService;

import java.util.Objects;
import java.util.Set;

/**
 * OpenAI 兼容通用 Provider（id "llm-openai-compat"，requires "llm"）：
 * adapter 注册名由组合位指定（也是 AgentOptions.provider 的路由键）——
 * 接入任意兼容厂商无需写插件，一条 CLI/组合配置即可（it7.1）。
 */
public final class OpenAiCompatPlugin implements Plugin {

    private final String adapterName;
    private final OpenAiCompatAdapter adapter;

    /**
     * @param adapterName adapter 注册名（路由键，如 "deepseek"、"kimi"、"local"）
     * @param profile     厂商差异（baseUrl/端点/附加头）
     * @param options     传输参数（含凭据；toString 脱敏）
     */
    public OpenAiCompatPlugin(String adapterName, VendorProfile profile, TransportOptions options) {
        Objects.requireNonNull(adapterName, "adapterName");
        if (adapterName.isEmpty()) {
            throw new IllegalArgumentException("adapterName must be non-empty");
        }
        this.adapterName = adapterName;
        this.adapter = new OpenAiCompatAdapter(profile, options);
    }

    @Override
    public String id() {
        return "llm-openai-compat";
    }

    @Override
    public Set<String> requires() {
        return Set.of("llm");
    }

    @Override
    public void apply(Scope scope) {
        LlmService llm = scope.require(LlmService.KEY);
        scope.onClose(llm.registerAdapter(adapterName, adapter));
    }
}
