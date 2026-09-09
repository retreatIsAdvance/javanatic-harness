package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** per-agent 作用域语义(06 §4):shadowing/兄弟隔离/同层重复/层随 scope 回收。 */
class ScopedRegistryTest {

    private static ToolDefinition tool(String name) {
        return ToolDefinition.of(name, name + " 描述",
            new ValueSchema.Object("参数", Map.of()), (a, c) -> ToolExecutionResult.success(name));
    }

    @Test
    void rootRegistrationVisibleFromChildScopes() throws Exception {
        try (Runtime rt = new Runtime()) {
            ScopedRegistry registry = new ScopedRegistry();
            registry.register(rt.root(), tool("bash"));
            Scope agent = rt.root().child();
            assertThat(registry.schemas(agent)).hasSize(1);
            assertThat(registry.resolve(agent, "bash")).isPresent();
        }
    }

    @Test
    void scopedRegistrationShadowsRootForSameName() throws Exception {
        try (Runtime rt = new Runtime()) {
            ScopedRegistry registry = new ScopedRegistry();
            registry.register(rt.root(), tool("bash"));
            Scope agent = rt.root().child();
            registry.register(agent, tool("bash"));
            assertThat(registry.schemas(agent)).hasSize(1);
            // 最近层胜:scoped 定义对 agent 可见,root 定义被盖住
            assertThat(registry.schemas(agent).getFirst().description()).isEqualTo("bash 描述");
        }
    }

    @Test
    void siblingScopesAreIsolated() throws Exception {
        try (Runtime rt = new Runtime()) {
            ScopedRegistry registry = new ScopedRegistry();
            Scope a = rt.root().child();
            Scope b = rt.root().child();
            registry.register(a, tool("only-a"));
            assertThat(registry.resolve(a, "only-a")).isPresent();
            assertThat(registry.resolve(b, "only-a")).isEmpty();
            assertThat(registry.schemas(b)).isEmpty();
        }
    }

    @Test
    void sameLayerDuplicateFailsLoudAcrossLayersShadow() throws Exception {
        try (Runtime rt = new Runtime()) {
            ScopedRegistry registry = new ScopedRegistry();
            registry.register(rt.root(), tool("x"));
            assertThatThrownBy(() -> registry.register(rt.root(), tool("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
            // 跨层同名合法(shadowing)
            Scope agent = rt.root().child();
            registry.register(agent, tool("x"));
            assertThat(registry.schemas(agent)).hasSize(1);
        }
    }

    @Test
    void layerReclaimedOnScopeClose() throws Exception {
        try (Runtime rt = new Runtime()) {
            ScopedRegistry registry = new ScopedRegistry();
            registry.register(rt.root(), tool("root-tool"));
            Scope agent = rt.root().child();
            registry.register(agent, tool("agent-tool"));
            assertThat(registry.schemas(rt.root())).hasSize(1);

            agent.close();
            // agent 层随 scope 关闭回收;root 不受影响
            assertThat(registry.schemas(rt.root())).hasSize(1);
            Scope sibling = rt.root().child();
            assertThat(registry.resolve(sibling, "agent-tool")).isEmpty();
        }
    }
}
