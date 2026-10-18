package io.javanatic.harness.mcp;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 连接注册表（mcp-registry）：排序稳定 / 查找缺省空 / 注销幂等且只摘自己 / 撞 id fail loud。 */
class McpRegistryPluginTest {

    private static Runtime runtimeWithRegistry() {
        Runtime rt = new Runtime();
        new PluginLoader().loadAll(rt, List.of(new McpRegistryPlugin()));
        return rt;
    }

    @Test
    void pluginProvidesSortedRegistryWithLookup() {
        try (Runtime rt = runtimeWithRegistry()) {
            assertThat(new McpRegistryPlugin().id()).isEqualTo("mcp-registry");
            McpClientService service = rt.root().require(McpClientService.KEY);
            service.register(new StubConnection("beta"));
            service.register(new StubConnection("alpha"));

            assertThat(service.connections()).extracting(McpServerConnection::serverId)
                .containsExactly("alpha", "beta"); // 按 id 排序（与注册顺序无关）
            assertThat(service.connection("alpha")).isPresent();
            assertThat(service.connection("missing")).isEmpty();
        }
    }

    @Test
    void duplicateServerIdFailsLoudAndKeepsRegisteredConnection() {
        try (Runtime rt = runtimeWithRegistry()) {
            McpClientService service = rt.root().require(McpClientService.KEY);
            StubConnection original = new StubConnection("alpha");
            service.register(original);

            assertThatThrownBy(() -> service.register(new StubConnection("alpha")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered")
                .hasMessageContaining("alpha");
            assertThat(service.connection("alpha")).containsSame(original);
        }
    }

    /** 注销幂等，且同 id 换绑后旧句柄不会误摘新连接。 */
    @Test
    void disposeRemovesOnlyItsOwnRegistrationAndIsIdempotent() {
        try (Runtime rt = runtimeWithRegistry()) {
            McpClientService service = rt.root().require(McpClientService.KEY);
            StubConnection first = new StubConnection("alpha");
            Disposable handle = service.register(first);

            handle.close();
            handle.close();
            assertThat(service.connection("alpha")).isEmpty();

            StubConnection second = new StubConnection("alpha");
            service.register(second);
            handle.close(); // 旧句柄再摘一次:不得误摘同 id 的新连接
            assertThat(service.connection("alpha")).containsSame(second);

            Disposable betaHandle = service.register(new StubConnection("beta"));
            betaHandle.close(); // 摘 beta，不动 alpha
            assertThat(service.connection("alpha")).containsSame(second);
            assertThat(service.connection("beta")).isEmpty();
        }
    }

    /** 连接桩：注册表测试只关心 id 身份与生命周期。 */
    private static final class StubConnection implements McpServerConnection {

        private final String serverId;

        StubConnection(String serverId) {
            this.serverId = serverId;
        }

        @Override
        public String serverId() {
            return serverId;
        }

        @Override
        public McpServerState state() {
            return McpServerState.CONNECTED;
        }

        @Override
        public List<McpToolDescriptor> tools() {
            return List.of();
        }

        @Override
        public long catalogGeneration() {
            return 1;
        }

        @Override
        public McpCallResult call(String toolName, String argumentsJson, AbortSignal signal) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
