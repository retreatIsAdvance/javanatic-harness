package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 数据组合路径:limits 从行配置解析,未给键用文档化默认(50/40)。 */
class LoopGuardConfigTest {

    @Test
    void defaultsWhenConfigEmpty() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            new PluginLoader().loadAll(rt, List.of(new LoopGuardPlugin()));
            LoopGuard.Limits limits = rt.root().require(LoopGuard.KEY).limits();
            assertThat(limits.maxTurns()).isEqualTo(LoopGuardPlugin.DEFAULT_MAX_TURNS);
            assertThat(limits.maxStepsPerTurn()).isEqualTo(LoopGuardPlugin.DEFAULT_MAX_STEPS_PER_TURN);
        }
    }

    @Test
    void explicitConfigWins() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "loop-guard".equals(id)
                ? Map.of("maxTurns", 5, "maxStepsPerTurn", "6") : Map.of());
            new PluginLoader().loadAll(rt, List.of(new LoopGuardPlugin()));
            LoopGuard.Limits limits = rt.root().require(LoopGuard.KEY).limits();
            assertThat(limits.maxTurns()).isEqualTo(5);
            assertThat(limits.maxStepsPerTurn()).isEqualTo(6);
        }
    }
}
