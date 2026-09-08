package io.javanatic.harness.llm.openai.compat;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.LlmService;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI 兼容通用 Provider（id "llm-openai-compat"，requires "llm"）：
 * adapter 注册名由组合位指定（也是 AgentOptions.provider 的路由键）——
 * 接入任意兼容厂商无需写插件，一条 CLI/组合配置即可（it7.1）。
 */
public final class OpenAiCompatPlugin implements Plugin {

    /** 数据组合路径的文档化默认。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_API_KEY_ENV = "DEEPSEEK_API_KEY";

    private final String explicitName;
    private final OpenAiCompatAdapter explicitAdapter;

    /** 数据组合路径：name/baseUrl/apiKeyEnv/apiKey 从行配置解析；无 key 时 fail loud（无 key 场景应 disabled 该行）。 */
    public OpenAiCompatPlugin() {
        this.explicitName = null;
        this.explicitAdapter = null;
    }

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
        this.explicitName = adapterName;
        this.explicitAdapter = new OpenAiCompatAdapter(profile, options);
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
        if (explicitAdapter != null) {
            scope.onClose(llm.registerAdapter(explicitName, explicitAdapter));
            return;
        }
        Map<String, Object> config = scope.require(ConfigService.KEY).configFor(id());
        String name = ConfigValues.requireString(config, id(), "name");
        String baseUrl = ConfigValues.stringValue(config, id(), "baseUrl", DEFAULT_BASE_URL);
        String apiKeyEnv = ConfigValues.stringValue(config, id(), "apiKeyEnv", DEFAULT_API_KEY_ENV);
        String literal = ConfigValues.stringValue(config, id(), "apiKey", null);
        String apiKey = literal != null ? literal : System.getenv(apiKeyEnv);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("llm-openai-compat: no api key (config 'apiKey' or env "
                + apiKeyEnv + "); keyless compositions should disable this row");
        }
        scope.onClose(llm.registerAdapter(name,
            new OpenAiCompatAdapter(VendorProfile.of(baseUrl),
                new TransportOptions(apiKey, null, null, 2, null, null))));
    }
}
