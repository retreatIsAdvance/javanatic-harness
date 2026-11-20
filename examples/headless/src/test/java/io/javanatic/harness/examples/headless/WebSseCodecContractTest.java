package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agentloop.AssistantChunkCodec;
import io.javanatic.harness.agentloop.AssistantChunkEvent;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.workbench.rpc.RpcHttpServer;
import io.javanatic.harness.workbench.rpc.SseEventSource;
import io.javanatic.harness.workbench.rpc.SseFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 assistant/chunk codec 跨模块 HTTP 往返，不借 timeline 投影或自造扩展 codec。 */
@Timeout(15)
class WebSseCodecContractTest {
    @Test
    void completeAssistantChunkVariantsSurviveHttpAndResumeFromRealSequence() throws Exception {
        List<StreamChunk> chunks = List.of(new StreamChunk.Delta("中文\nid: forged"),
                new StreamChunk.DeltaToolUse(CallId.of("tool-42"), "probe", "{\"x\":"),
                new StreamChunk.Usage(new TokenUsage(21, 13, 8)), new StreamChunk.Finish(FinishReason.STOP));
        Session session = Session.create(Session.newId("sse-codec"), null, null);
        for (StreamChunk chunk : chunks) {
            session.append(new AssistantChunkEvent(123, 7, 2, chunk));
        }
        SessionCodecRegistry codecs = new SessionCodecRegistry();
        codecs.register(new AssistantChunkCodec());
        AtomicInteger closed = new AtomicInteger();
        SseEventSource source = (id, after) -> {
            assertThat(id).isEqualTo(session.id());
            var iterator = session.events().stream().filter(event -> event.seq() > after).iterator();
            return new SseEventSource.Opened() {
                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public long head() {
                    return 3;
                }

                @Override
                public Optional<SseFrame> next() {
                    if (!iterator.hasNext()) {
                        return Optional.empty();
                    }
                    LoggedEvent<?> event = iterator.next();
                    return Optional.of(new SseFrame.Persisted(event));
                }

                @Override
                public void close() {
                    if (done.compareAndSet(false, true)) {
                        closed.incrementAndGet();
                    }
                }
            };
        };
        try (RpcHttpServer server = new RpcHttpServer((method, params) -> null, source, codecs)) {
            server.start(0);
            String first = fetch(server, null);
            List<String> data = first.lines().filter(line -> line.startsWith("data: ")).toList();
            assertThat(first.lines().filter(line -> line.startsWith("id: ")).toList())
                    .containsExactly("id: 0", "id: 1", "id: 2", "id: 3");
            assertThat(data).hasSize(4);
            for (String line : data) {
                assertThat(line).contains("\"type\":\"assistant/chunk\"", "\"ignorable\":true",
                        "\"time\":123", "\"turn\":7", "\"step\":2", "\"chunk\":{");
            }
            assertThat(data.get(0)).contains("\"kind\":\"delta\"", "\"text\":\"中文\\nid: forged\"");
            assertThat(data.get(1)).contains("\"kind\":\"tool-use\"", "\"callId\":\"tool-42\"",
                    "\"name\":\"probe\"", "\"argumentsDelta\":\"{\\\"x\\\":\"");
            assertThat(data.get(2)).contains("\"kind\":\"usage\"", "\"input\":21", "\"output\":13", "\"reasoning\":8");
            assertThat(data.get(3)).contains("\"kind\":\"finish\"", "\"reason\":\"stop\"");
            String resumed = fetch(server, "1");
            assertThat(resumed.lines().filter(line -> line.startsWith("id: ")).toList())
                    .containsExactly("id: 2", "id: 3");
            assertThat(resumed.lines().filter(line -> line.startsWith("data: ")).toList())
                    .containsExactly(data.get(2), data.get(3));
        }
        assertThat(closed.get()).isEqualTo(2);
    }

    private static String fetch(RpcHttpServer server, String lastId) throws Exception {
        var address = server.address();
        URI uri = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(),
                "/events", "session=sse-codec", null);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setReadTimeout(5000);
        connection.setConnectTimeout(5000);
        if (lastId != null) {
            connection.setRequestProperty("Last-Event-ID", lastId);
        }
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            assertThat(connection.getHeaderField("Content-Type")).startsWith("text/event-stream");
            try (var input = connection.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
