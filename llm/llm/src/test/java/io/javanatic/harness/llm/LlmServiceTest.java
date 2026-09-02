package io.javanatic.harness.llm;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.message.Message;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 路由服务：分发、fail-loud 清单、注册/注销闭环。 */
class LlmServiceTest {

    /** 记录调用并回放固定分块的假 adapter。 */
    private record FakeAdapter(List<StreamChunk> chunks) implements LlmAdapter {
        @Override
        public java.util.stream.Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request,
                                                            AbortSignal signal) {
            return chunks.stream();
        }
    }

    private static final LlmCallConfig CONFIG = new LlmCallConfig("fake", "m1");
    private static final LlmRequest REQUEST = new LlmRequest(null,
        List.of(UserMessage.of("hi", new MessageSource.User())), List.of(), java.util.Map.of());

    @Test
    void pluginProvidesRoutingService() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new LlmPlugin()));
            LlmService llm = rt.root().require(LlmService.KEY);
            llm.registerAdapter("fake", new FakeAdapter(List.of(new StreamChunk.Finish(FinishReason.STOP))));
            List<StreamChunk> out = llm.stream(CONFIG, REQUEST, AbortSignal.never()).toList();
            assertThat(out).hasSize(1);
        }
    }

    @Test
    void unknownProviderFailsLoudListingRegistered() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new LlmPlugin()));
            LlmService llm = rt.root().require(LlmService.KEY);
            llm.registerAdapter("zeta", new FakeAdapter(List.of()));
            assertThatThrownBy(() -> llm.stream(CONFIG, REQUEST, AbortSignal.never()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake")
                .hasMessageContaining("zeta"); // 诊断清单就地给出
        }
    }

    @Test
    void duplicateRegistrationFailsLoud() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new LlmPlugin()));
            LlmService llm = rt.root().require(LlmService.KEY);
            llm.registerAdapter("fake", new FakeAdapter(List.of()));
            assertThatThrownBy(() -> llm.registerAdapter("fake", new FakeAdapter(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake");
        }
    }

    @Test
    void disposableUnregistersAndIsIdempotent() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new LlmPlugin()));
            LlmService llm = rt.root().require(LlmService.KEY);
            Disposable handle = llm.registerAdapter("fake",
                new FakeAdapter(List.of(new StreamChunk.Finish(FinishReason.STOP))));
            handle.close();
            handle.close(); // 幂等：第二次空操作
            assertThat(handle.isClosed()).isTrue();
            assertThatThrownBy(() -> llm.stream(CONFIG, REQUEST, AbortSignal.never()))
                .hasMessageContaining("no llm adapter");
        }
    }

    @Test
    void requestTravelsToAdapterVerbatim() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new LlmPlugin()));
            LlmService llm = rt.root().require(LlmService.KEY);
            List<LlmRequest> seen = new java.util.ArrayList<>();
            ToolSchema schema = new ToolSchema("fs_read", "read", "{}");
            LlmAdapter recorder = (config, request, signal) -> {
                seen.add(request);
                return java.util.stream.Stream.of(new StreamChunk.Finish(FinishReason.STOP));
            };
            llm.registerAdapter("fake", recorder);
            LlmRequest withTools = new LlmRequest("sys", REQUEST.messages(), List.of(schema),
                java.util.Map.of("k", "v"));
            llm.stream(CONFIG, withTools, AbortSignal.never()).collect(Collectors.toList());
            assertThat(seen.getFirst().system()).isEqualTo("sys");
            assertThat(seen.getFirst().tools()).containsExactly(schema);
            assertThat(seen.getFirst().params()).containsEntry("k", "v");
            assertThat(seen.getFirst().messages().getFirst()).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) (Message) seen.getFirst().messages().getFirst()).content()).hasSize(1);
        }
    }
}
