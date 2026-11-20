package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javanatic.harness.session.event.ExtensionEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.persistence.JsonValue;
import io.javanatic.harness.session.persistence.SessionCodecRegistry;
import io.javanatic.harness.session.persistence.SessionEventCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(5)
class SseEncoderTest {
    static final String PAYLOAD = "中文\n\nid: 999\r\ndata: forged";

    @Test
    void persistedFrameKeepsCompleteExtensionEnvelopeWithoutLineInjection() throws Exception {
        String wire = text(new SseEncoder(codecs()).encode(frame(0)));
        assertThat(wire.lines().toList()).hasSize(3);
        assertThat(wire).startsWith("id: 0\ndata: ").endsWith("\n\n");
        JsonNode envelope = new ObjectMapper().readTree(wire.lines().skip(1).findFirst().orElseThrow().substring(6));
        assertThat(envelope.size()).isEqualTo(4);
        assertThat(envelope.get("seq").asLong()).isZero();
        assertThat(envelope.get("type").asText()).isEqualTo("test/chunk");
        assertThat(envelope.get("ignorable").asBoolean()).isTrue();
        JsonNode data = envelope.get("data");
        assertThat(data.get("text").asText()).isEqualTo(PAYLOAD);
        assertThat(data.get("nested").get("values").get(0).asLong()).isEqualTo(7);
        assertThat(data.get("nested").get("values").get(1).asBoolean()).isTrue();
        assertThat(data.get("nested").get("values").get(2).isNull()).isTrue();
    }

    @Test
    void controlAndKeepaliveNeverSetReconnectId() {
        SseEncoder encoder = new SseEncoder(codecs());
        String control = text(encoder.encode(new SseFrame.Control(
            JsonValue.object().set("type", "approval/snapshot").set("text", PAYLOAD).build())));
        assertThat(control).startsWith("data: ").endsWith("\n\n");
        assertThat(control.lines().filter(line -> line.startsWith("id:"))).isEmpty();
        assertThat(text(SseEncoder.keepalive())).isEqualTo(": keepalive\n\n");
    }

    @Test
    void missingCodecFailsLoudRatherThanProjectingAwayPayload() {
        assertThatThrownBy(() -> new SseEncoder(new SessionCodecRegistry()).encode(frame(3)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("test/chunk");
    }

    @Test
    void negativePersistedSequenceIsNotAControlFrame() {
        assertThatThrownBy(() -> frame(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    static SseFrame.Persisted frame(long seq) {
        return new SseFrame.Persisted(new LoggedEvent<>(seq, new Chunk(PAYLOAD)));
    }

    static SessionCodecRegistry codecs() {
        SessionCodecRegistry registry = new SessionCodecRegistry();
        registry.register(new ChunkCodec());
        return registry;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    record Chunk(String text) implements ExtensionEvent {
        @Override
        public long time() {
            return 123L;
        }

        @Override
        public String type() {
            return "test/chunk";
        }

        @Override
        public boolean ignorable() {
            return true;
        }
    }

    private static final class ChunkCodec implements SessionEventCodec<Chunk> {
        @Override
        public String type() {
            return "test/chunk";
        }

        @Override
        public Class<Chunk> typeClass() {
            return Chunk.class;
        }

        @Override
        public JsonValue.Obj write(Chunk event) {
            return JsonValue.object().set("text", event.text()).set("nested", JsonValue.object()
                .set("values", JsonValue.array(new JsonValue.Num(7), new JsonValue.Bool(true), JsonValue.Null.INSTANCE))
                .build()).build();
        }

        @Override
        public Chunk read(JsonValue.Obj body) {
            return new Chunk(body.get("text").asString());
        }
    }
}
