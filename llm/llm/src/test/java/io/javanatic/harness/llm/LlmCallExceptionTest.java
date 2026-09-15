package io.javanatic.harness.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kind 词表的可重试性契约：路由方（适配器/交互面）以此为唯一依据。 */
class LlmCallExceptionTest {

    @Test
    void retryableKindsAreExactlyRateLimitServerNetwork() {
        assertThat(LlmCallException.Kind.RATE_LIMIT.retryable()).isTrue();
        assertThat(LlmCallException.Kind.SERVER.retryable()).isTrue();
        assertThat(LlmCallException.Kind.NETWORK.retryable()).isTrue();
        assertThat(LlmCallException.Kind.AUTH.retryable()).isFalse();
        assertThat(LlmCallException.Kind.TIMEOUT.retryable()).isFalse();
        assertThat(LlmCallException.Kind.PROTOCOL.retryable()).isFalse();
        assertThat(LlmCallException.Kind.OVERFLOW.retryable()).isFalse();
    }

    @Test
    void kindAndCauseArePreserved() {
        IllegalStateException cause = new IllegalStateException("wire");
        LlmCallException e = new LlmCallException(LlmCallException.Kind.PROTOCOL, "bad", cause);
        assertThat(e.kind()).isEqualTo(LlmCallException.Kind.PROTOCOL);
        assertThat(e).hasMessage("bad").hasCause(cause);
    }
}
