package io.javanatic.harness.llm.replay;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 回放 Provider：脚本顺序、耗尽 fail loud、取消协议、插件装配与回滚（R3）。 */
class ReplayTest {

    private static final LlmCallConfig CONFIG = new LlmCallConfig("replay", "test");
    private static final LlmRequest REQUEST = new LlmRequest(null,
        List.of(UserMessage.of("hi", new MessageSource.User())), List.of(), Map.of());

    @Test
    void scriptsReplayInOrderThenExhaustFailsLoud() {
        ReplayAdapter adapter = new ReplayAdapter(List.of(
            List.of(new StreamChunk.Delta("一"), new StreamChunk.Finish(FinishReason.STOP)),
            List.of(new StreamChunk.Delta("二"), new StreamChunk.Finish(FinishReason.STOP))));
        ChunkAssembly.Assembled first = ChunkAssembly.fold(
            adapter.stream(CONFIG, REQUEST, AbortSignal.never()).toList());
        ChunkAssembly.Assembled second = ChunkAssembly.fold(
            adapter.stream(CONFIG, REQUEST, AbortSignal.never()).toList());
        assertThat(first.text()).isEqualTo("一");
        assertThat(second.text()).isEqualTo("二");
        assertThatThrownBy(() -> adapter.stream(CONFIG, REQUEST, AbortSignal.never()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exhausted");
    }

    @Test
    void constructionFreezesScriptsDeeply() {
        List<StreamChunk> inner = new java.util.ArrayList<>(List.of(
            new StreamChunk.Delta("原文"), new StreamChunk.Finish(FinishReason.STOP)));
        ReplayAdapter adapter = new ReplayAdapter(List.of(inner));
        inner.set(0, new StreamChunk.Delta("被改了")); // 外层 List.of 挡不住内层可变引用
        ChunkAssembly.Assembled out = ChunkAssembly.fold(
            adapter.stream(CONFIG, REQUEST, AbortSignal.never()).toList());
        assertThat(out.text()).isEqualTo("原文");
    }

    @Test
    void abortSignalIsCheckedBetweenChunks() {
        AtomicBoolean aborted = new AtomicBoolean(false);
        AbortSignal signal = () -> {
            if (aborted.get()) {
                throw new io.javanatic.harness.llm.AbortedException("aborted");
            }
        };
        ReplayAdapter adapter = new ReplayAdapter(List.of(List.of(
            new StreamChunk.Delta("a"),
            new StreamChunk.Delta("b"),
            new StreamChunk.Finish(FinishReason.STOP))));
        var iterator = adapter.stream(CONFIG, REQUEST, signal).iterator();
        assertThat(iterator.next()).isEqualTo(new StreamChunk.Delta("a"));
        aborted.set(true);
        assertThatThrownBy(iterator::hasNext)
            .isInstanceOf(io.javanatic.harness.llm.AbortedException.class);
    }

    @Test
    void pluginAssemblesAndStreamsThroughSeam() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new io.javanatic.harness.llm.LlmPlugin(),
                new ReplayPlugin(List.of(List.of(
                    new StreamChunk.Delta("回放"),
                    new StreamChunk.Finish(FinishReason.STOP))))));
            LlmService llm = rt.root().require(LlmService.KEY);
            ChunkAssembly.Assembled out = ChunkAssembly.fold(
                llm.stream(CONFIG, REQUEST, AbortSignal.never()).toList());
            assertThat(out.text()).isEqualTo("回放");
        }
    }

    @Test
    void requiresGuardsLoadOrder() {
        try (Runtime rt = new Runtime()) {
            // 缺 "llm"：apply 内 require(LlmService.KEY) fail loud
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new ReplayPlugin(List.of()))))
                .isInstanceOf(Exception.class);
        }
    }

    /** 注册成功后 apply 抛异常的插件：回滚必须带走 adapter 注册（R3 端到端）。 */
    @Test
    void pluginFailureRollsBackAdapterRegistration() {
        try (Runtime rt = new Runtime()) {
            Plugin broken = new Plugin() {
                @Override
                public String id() {
                    return "llm-replay";
                }

                @Override
                public void apply(Scope scope) {
                    new ReplayPlugin(List.of()).apply(scope);
                    throw new IllegalStateException("boom after registration");
                }
            };
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(
                new io.javanatic.harness.llm.LlmPlugin(), broken)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back");
            LlmService llm = rt.root().require(LlmService.KEY);
            assertThatThrownBy(() -> llm.stream(CONFIG, REQUEST, AbortSignal.never()))
                .hasMessageContaining("no llm adapter") // 注册随插件私有 scope 消失
                .hasMessageContaining("(registered: )"); // 清单为空：回滚带走了注册
        }
    }
}
