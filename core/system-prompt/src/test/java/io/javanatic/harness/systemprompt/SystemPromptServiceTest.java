package io.javanatic.harness.systemprompt;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.ProjectInstructions;
import io.javanatic.harness.session.event.RequestHeader;

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

    /** it21 项目说明：位置在上下文段之后、注册段之前；内容与事件逐字一致。 */
    @Test
    void projectInstructionsRenderAfterContextBeforeRegisteredSections() {
        Session session = Session.create(Session.newId("sp2"), null, null);
        session.append(new RequestHeader(1, "/ws", "2026-09-22"));
        session.append(new ProjectInstructions(2, "/ws/AGENTS.md", "sha-1", false,
            "# 规则\n用 mvn 构建"));
        SystemPromptImpl prompts = new SystemPromptImpl();
        prompts.register(new PromptSection.Static(100, "tail"));

        assertThat(prompts.assemble(session)).isEqualTo("""
            Current context:
            - working directory: /ws
            - date: 2026-09-22

            Project instructions (/ws/AGENTS.md):
            # 规则
            用 mvn 构建

            tail""");
    }

    /** 最新事件胜出（改过说明文件即换内容）；截断在提示词里显形。 */
    @Test
    void latestInstructionsWinAndTruncationIsMarked() {
        Session session = Session.create(Session.newId("sp3"), null, null);
        session.append(new ProjectInstructions(1, "/ws/AGENTS.md", "sha-1", false, "旧的"));
        session.append(new ProjectInstructions(2, "/ws/AGENTS.md", "sha-2", true, "新的"));

        assertThat(new SystemPromptImpl().assemble(session))
            .isEqualTo("Project instructions (/ws/AGENTS.md):\n新的\n… (truncated)");
    }
}
