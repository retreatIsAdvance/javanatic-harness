package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 组装语义：priority 稳定排序、注销移除、空注册空串、插件提供服务。 */
class SystemPromptServiceTest {

    private static final Session SESSION = Session.create(Session.newId("sp"), null, null);

    @Test
    void assemblesByPriorityWithStableTieOrder() {
        SystemPromptImpl prompts = new SystemPromptImpl();
        prompts.register(new PromptSection.Static(10, "tail"));
        prompts.register(new PromptSection.Static(0, "head"));
        prompts.register(new PromptSection.Static(10, "tail-second"));

        assertThat(prompts.assemble(SESSION)).isEqualTo("head\n\ntail\n\ntail-second");
    }

    @Test
    void unregisterRemovesSection() {
        SystemPromptImpl prompts = new SystemPromptImpl();
        Disposable handle = prompts.register(new PromptSection.Static(0, "a"));
        prompts.register(new PromptSection.Static(1, "b"));
        handle.close();

        assertThat(prompts.assemble(SESSION)).isEqualTo("b");
    }

    @Test
    void noSectionsAssembleToEmptyString() {
        assertThat(new SystemPromptImpl().assemble(SESSION)).isEmpty();
    }

    @Test
    void pluginProvidesService() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SystemPromptPlugin()));
            SystemPromptService prompts = rt.root().require(SystemPromptService.KEY);
            assertThat(prompts.assemble(SESSION)).isEmpty();
        }
    }
}
