package io.javanatic.harness.workbench.rpc;

import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(5)
class SseRequestTest {
    @Test
    void malformedEscapesUtf8AndMultivaluesAreTyped400() {
        for (String query : List.of("session=%", "session=%GG", "session=%C3%28", "session=%FF",
                "session=%00", "session=s1&lastSeq=1&lastSeq=2", "session=s1&%73ession=s2")) {
            assertThatThrownBy(() -> SseRequest.parse(query, List.of()))
                .isInstanceOf(SseEventSource.HttpFailureException.class)
                .satisfies(error -> assertThat(((SseEventSource.HttpFailureException) error).status()).isEqualTo(400));
        }
        for (List<String> headers : List.of(List.of("0", "1"), List.of("0,1"), List.of(""), List.of("-2"))) {
            assertThatThrownBy(() -> SseRequest.parse("session=s1", headers))
                .isInstanceOf(SseEventSource.HttpFailureException.class);
        }
    }

    @Test
    void rawUnicodeSessionDoesNotSilentlyBecomeQuestionMarks() {
        assertThat(SseRequest.parse("session=中文", List.of()).session()).isEqualTo(new Id<Session>("中文"));
    }

    @Test
    void decodedBrandAndHeaderPrecedenceRemainExplicit() {
        var request = SseRequest.parse("session=%E4%B8%AD%E6%96%87&lastSeq=0", List.of("1"));
        assertThat(request.session()).isEqualTo(new Id<Session>("中文"));
        assertThat(request.afterSeq()).isEqualTo(1);
        assertThat(SseRequest.parse("session=s1", List.of()).afterSeq()).isEqualTo(-1);
    }
}
