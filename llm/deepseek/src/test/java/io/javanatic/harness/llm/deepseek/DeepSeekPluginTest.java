package io.javanatic.harness.llm.deepseek;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 薄壳冒烟:插件经 seam 注册 "deepseek" 并流式到假服务端(真 e2e 见 DeepSeekE2ETest)。 */
class DeepSeekPluginTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void pluginRegistersDeepSeekAdapterThroughSeam() throws Exception {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(
                new LlmPlugin(),
                new DeepSeekPlugin(new DeepSeekOptions(
                    "http://localhost:" + server.getAddress().getPort(),
                    "test-key", null, null, 2, null, null))));
            LlmService llm = rt.root().require(LlmService.KEY);
            try (Stream<StreamChunk> chunks = llm.stream(
                    new LlmCallConfig("deepseek", "deepseek-chat"),
                    new LlmRequest(null,
                        List.of(UserMessage.of("hi", new MessageSource.User())),
                        List.of(), Map.of()),
                    AbortSignal.never())) {
                ChunkAssembly.Assembled assembled = ChunkAssembly.fold(chunks.toList());
                assertThat(assembled.text()).isEqualTo("hi");
                assertThat(assembled.finishReason()).isEqualTo(FinishReason.STOP);
            }
        }
    }
}
