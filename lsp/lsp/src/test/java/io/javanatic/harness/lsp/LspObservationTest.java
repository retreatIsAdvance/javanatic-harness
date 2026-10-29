package io.javanatic.harness.lsp;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 观察值约束，不冒充真实文件/时钟/Provider 验证。 */
class LspObservationTest {
    private static final URI ROOT = URI.create("file:///project/");
    private static final URI FILE = URI.create("file:///project/App.java");
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String HASH = "a".repeat(64);
    private static final Duration WINDOW = Duration.ofSeconds(1);

    @Test
    void observedRequiresStableAnchorAndCompleteWindow() {
        LspAnchor before = anchor(0, HASH);
        assertThatIllegalArgumentException().isThrownBy(() -> observation(before, anchor(0, "b".repeat(64)), 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> observation(before, before, 999));
        assertThat(observation(before, before, 1000).elapsedWait()).isEqualTo(Duration.ofMillis(1000));
    }

    @Property(tries = 100)
    void everyDifferentGenerationRejectsObserved(@ForAll @IntRange(min = 1, max = 1000000) int generation) {
        assertThatIllegalArgumentException().isThrownBy(() -> observation(anchor(0, HASH), anchor(generation, HASH), 1000));
    }

    @Test
    void restartAndDocumentHashChangeCannotBeHiddenBySameProjectHash() {
        LspAnchor before = anchor(0, HASH);
        LspAnchor restarted = new LspAnchor(ROOT, UUID.fromString("00000000-0000-0000-0000-000000000002"),
            0, HASH, before.document());
        LspAnchor edited = new LspAnchor(ROOT, RUN, 0, HASH, new LspDocument(FILE, Optional.of("b".repeat(64))));
        assertThatIllegalArgumentException().isThrownBy(() -> observation(before, restarted, 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> observation(before, edited, 1000));
    }

    @Test
    void missingFileIsStaleButNeverSuccessfulObservation() {
        LspAnchor missing = new LspAnchor(ROOT, RUN, 1, HASH, new LspDocument(FILE, Optional.empty()));
        LspResult<String> result = new LspResult.Stale<>(anchor(0, HASH), missing);
        assertThat(result).isInstanceOf(LspResult.Stale.class);
        assertThatIllegalArgumentException().isThrownBy(() -> observation(missing, missing, 1000));
    }

    @Test
    void staleRequiresChangeWithinSameQueryTarget() {
        LspAnchor before = anchor(0, HASH);
        assertThatIllegalArgumentException().isThrownBy(() -> new LspResult.Stale<>(before, before));
        LspAnchor other = new LspAnchor(ROOT, RUN, 1, HASH,
            new LspDocument(URI.create("file:///project/Other.java"), Optional.of(HASH)));
        assertThatIllegalArgumentException().isThrownBy(() -> new LspResult.Stale<>(before, other));
        LspAnchor otherRoot = new LspAnchor(URI.create("file:///other/"), RUN, 1, HASH, before.document());
        assertThatIllegalArgumentException().isThrownBy(() -> new LspResult.Stale<>(before, otherRoot));
    }

    @Test
    void hashesAndGenerationAreValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> anchor(-1, HASH));
        for (String invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThatIllegalArgumentException().isThrownBy(() -> anchor(0, invalid));
            assertThatIllegalArgumentException().isThrownBy(() -> new LspDocument(FILE, Optional.of(invalid)));
        }
        assertThatNullPointerException().isThrownBy(() -> new LspDocument(FILE, null));
        assertThatNullPointerException().isThrownBy(() -> new LspAnchor(ROOT, null, 0, HASH, anchor(0, HASH).document()));
    }

    @Test
    void observedCopiesItemsAndRejectsNullEntries() {
        List<String> items = new ArrayList<>(List.of("warning"));
        LspResult.Observed<String> result = new LspResult.Observed<>(observation(anchor(0, HASH), anchor(0, HASH), 1000), items);
        items.clear();
        assertThat(result.items()).containsExactly("warning");
        assertThatThrownBy(() -> result.items().add("error")).isInstanceOf(UnsupportedOperationException.class);
        items.add(null);
        assertThatNullPointerException().isThrownBy(() -> new LspResult.Observed<>(result.observation(), items));
        assertThatNullPointerException().isThrownBy(() -> new LspResult.Observed<String>(null, List.of()));
    }

    @Test
    void emptyPublicationKeepsVersionAndDiffersFromQuietWindow() {
        LspEvidence published = new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 400, OptionalInt.of(2));
        LspEvidence quiet = new LspEvidence(LspEvidence.Origin.QUIET_WINDOW, 400, OptionalInt.empty());
        LspAnchor anchor = anchor(0, HASH);
        LspObservation observation = new LspObservation(anchor, anchor, WINDOW, WINDOW, Instant.EPOCH, published);
        LspResult.Observed<String> empty = new LspResult.Observed<>(observation, List.of());
        assertThat(empty.observation().evidence().serverVersion()).hasValue(2);
        assertThat(empty.observation().evidence()).isNotEqualTo(quiet);
        LspObservation silence = new LspObservation(anchor, anchor, WINDOW, WINDOW, Instant.EPOCH, quiet);
        assertThat(new LspResult.Observed<String>(silence, List.of()).items()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new LspResult.Observed<>(silence, List.of("warning")));
    }

    @Test
    void publicationMetadataCannotInventVersionOrNegativeSequence() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new LspEvidence(LspEvidence.Origin.QUIET_WINDOW, 0, OptionalInt.of(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
            new LspEvidence(LspEvidence.Origin.REQUEST_RESPONSE, 1, OptionalInt.of(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
            new LspEvidence(LspEvidence.Origin.REQUEST_RESPONSE, -1, OptionalInt.empty()));
        assertThat(new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 1, OptionalInt.of(-2)).serverVersion()).hasValue(-2);
    }

    @Test
    void failuresAreNotSuccessfulEmptyResults() {
        assertThat(describe(new LspResult.NotReady<String>(LspResult.NotReadyReason.INITIALIZING))).isEqualTo("not-ready");
        assertThat(describe(new LspResult.Stale<String>(anchor(0, HASH), anchor(1, HASH)))).isEqualTo("stale");
        assertThat(describe(new LspResult.TimedOut<String>(WINDOW))).isEqualTo("timeout");
        assertThat(describe(new LspResult.Observed<String>(observation(anchor(0, HASH), anchor(0, HASH), 1000), List.of())))
            .isEqualTo("observed:0");
        assertThatIllegalArgumentException().isThrownBy(() -> new LspResult.TimedOut<String>(Duration.ZERO));
        assertThatNullPointerException().isThrownBy(() -> new LspResult.NotReady<String>(null));
    }

    @Test
    void observationRejectsMissingTimeAndNonpositiveWindow() {
        LspAnchor anchor = anchor(0, HASH);
        LspEvidence evidence = new LspEvidence(LspEvidence.Origin.QUIET_WINDOW, 0, OptionalInt.empty());
        assertThatIllegalArgumentException().isThrownBy(() ->
            new LspObservation(anchor, anchor, Duration.ZERO, WINDOW, Instant.EPOCH, evidence));
        assertThatNullPointerException().isThrownBy(() ->
            new LspObservation(anchor, anchor, WINDOW, WINDOW, null, evidence));
    }

    private static String describe(LspResult<String> result) {
        return switch (result) {
            case LspResult.Observed<String> observed -> "observed:" + observed.items().size();
            case LspResult.NotReady<String> ignored -> "not-ready";
            case LspResult.Stale<String> ignored -> "stale";
            case LspResult.TimedOut<String> ignored -> "timeout";
            default -> throw new IllegalStateException("unexpected LSP result: " + result);
        };
    }

    private static LspAnchor anchor(long generation, String hash) {
        return new LspAnchor(ROOT, RUN, generation, hash, new LspDocument(FILE, Optional.of(HASH)));
    }

    private static LspObservation observation(LspAnchor before, LspAnchor after, long elapsedMillis) {
        return new LspObservation(before, after, WINDOW, Duration.ofMillis(elapsedMillis), Instant.EPOCH,
            new LspEvidence(LspEvidence.Origin.PUBLISHED_DIAGNOSTICS, 379, OptionalInt.empty()));
    }
}
