package io.javanatic.harness.llm.openai.compat;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据组合路径:name 必填、key 缺失 fail loud(行应 disabled)、注册名可路由。 */
class OpenAiCompatConfigTest {

    private static LlmRequest request() {
        return new LlmRequest(null, List.of(UserMessage.of("hi", new MessageSource.User())),
            List.of(), Map.of());
    }

    @Test
    void configRegistersNamedAdapterWithoutHttpCall() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "llm-openai-compat".equals(id)
                ? Map.of("name", "vendor-x", "apiKey", "fake") : Map.of());
            new PluginLoader().loadAll(rt, List.of(new LlmPlugin(), new OpenAiCompatPlugin()));
            LlmService llm = rt.root().require(LlmService.KEY);
            // 路由错误信息包含已注册清单——无需 HTTP 即证明注册名来自 config
            assertThatThrownBy(() -> llm.stream(new LlmCallConfig("nope", "m"), request(),
                    AbortSignal.never()).toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vendor-x");
        }
    }

    @Test
    void missingKeyFailsLoudAtLoad() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "llm-openai-compat".equals(id)
                ? Map.of("name", "vendor-x") : Map.of());
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                    List.of(new LlmPlugin(), new OpenAiCompatPlugin())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("llm-openai-compat: no api key (config 'apiKey' or env "
                    + OpenAiCompatPlugin.DEFAULT_API_KEY_ENV + "); keyless compositions should disable this row");
        }
    }
}
