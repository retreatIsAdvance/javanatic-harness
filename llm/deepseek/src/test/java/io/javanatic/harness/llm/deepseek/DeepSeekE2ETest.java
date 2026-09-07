package io.javanatic.harness.llm.deepseek;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.ChunkAssembly;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.openai.compat.OpenAiCompatAdapter;
import io.javanatic.harness.llm.openai.compat.TransportOptions;
import io.javanatic.harness.llm.openai.compat.VendorProfile;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.UserMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 DeepSeek API 冒烟（无 key 自跳过——keyless 是门禁纪律，真实验证是
 * 有 key 环境的增量证据）。验证 wire 兼容:SSE、UTF-8、usage、finish。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekE2ETest {

    @Test
    void realCompletionRoundTrips() {
        OpenAiCompatAdapter adapter = new OpenAiCompatAdapter(
            VendorProfile.of(DeepSeekOptions.DEFAULT_BASE_URL),
            new TransportOptions(System.getenv("DEEPSEEK_API_KEY"), null, null, 2, null, null));
        LlmRequest request = new LlmRequest(null,
            List.of(UserMessage.of("用恰好四个汉字回答:1+1 等于几?", new MessageSource.User())),
            List.of(), Map.of());
        ChunkAssembly.Assembled assembled;
        try (Stream<StreamChunk> chunks = adapter.stream(
                new LlmCallConfig("deepseek", "deepseek-chat"), request, AbortSignal.never())) {
            assembled = ChunkAssembly.fold(chunks.toList());
        }
        assertThat(assembled.text()).contains("二");
        assertThat(assembled.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(assembled.usage().inputTokens()).isPositive();
        assertThat(assembled.usage().outputTokens()).isPositive();
    }
}
