package io.javanatic.harness.lsp.jdtls;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspPosition;
import io.javanatic.harness.lsp.LspRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiagnosticsCache 候选批次缓存：每文档只留最新批次（截点归属）、条数/字节双帽、
 * 溢出置位（Provider 拉取时映射 PROTOCOL）、版本捕获、clear 复位。
 */
class DiagnosticsCacheTest {

    private static final URI DOC = URI.create("file:///project/src/App.java");

    private final DiagnosticsCache cache = new DiagnosticsCache();

    private static List<LspDiagnostic> items(int count, String message) {
        return IntStream.range(0, count)
            .mapToObj(i -> new LspDiagnostic(
                new LspRange(new LspPosition(i, 0), new LspPosition(i, 1)),
                LspDiagnostic.Severity.ERROR, Optional.of("E" + i), Optional.of("Java"),
                message))
            .toList();
    }

    @Test
    void latestBatchWithinCutoffIsReturned() {
        cache.publish(DOC, 5L, OptionalInt.empty(), items(1, "first"));
        cache.publish(DOC, 9L, OptionalInt.empty(), items(2, "second"));
        assertThat(cache.snapshot(DOC, 10L)).isPresent();
        DiagnosticsCache.Batch batch = cache.snapshot(DOC, 10L).orElseThrow();
        assertThat(batch.sequence()).isEqualTo(9L);
        assertThat(batch.items()).hasSize(2);
        assertThat(batch.items().get(0).message()).isEqualTo("second");
    }

    @Test
    void batchAboveCutoffIsExcluded() {
        cache.publish(DOC, 12L, OptionalInt.empty(), items(1, "late"));
        assertThat(cache.snapshot(DOC, 10L)).isEmpty();
    }

    @Test
    void versionIsCapturedAndEmptyVersionStaysEmpty() {
        cache.publish(DOC, 3L, OptionalInt.of(42), items(1, "versioned"));
        assertThat(cache.snapshot(DOC, 3L).orElseThrow().version()).hasValue(42);
        cache.publish(DOC, 4L, OptionalInt.empty(), items(1, "unversioned"));
        assertThat(cache.snapshot(DOC, 4L).orElseThrow().version()).isEmpty();
    }

    @Test
    void emptyBatchIsDistinctFromNoBatch() {
        cache.publish(DOC, 7L, OptionalInt.empty(), List.of());
        assertThat(cache.snapshot(DOC, 7L)).isPresent();
        assertThat(cache.snapshot(DOC, 7L).orElseThrow().items()).isEmpty();
    }

    @Test
    void exceedingEntryCapMarksOverflow() {
        cache.publish(DOC, 1L, OptionalInt.empty(),
            items(DiagnosticsCache.MAX_ENTRIES_PER_DOCUMENT + 1, "x"));
        assertThat(cache.overflowed(DOC, 1L)).isTrue();
        assertThat(cache.overflowed(DOC, 0L)).isFalse(); // 截点前的溢出归下一次观察
        assertThat(cache.snapshot(DOC, 1L)).isEmpty();
    }

    @Test
    void exceedingByteCapMarksOverflow() {
        cache.publish(DOC, 1L, OptionalInt.empty(),
            items(8, "y".repeat(DiagnosticsCache.MAX_BYTES_PER_DOCUMENT / 4)));
        assertThat(cache.overflowed(DOC, 1L)).isTrue();
    }

    @Test
    void clearResetsBatchesAndOverflow() {
        cache.publish(DOC, 2L, OptionalInt.empty(), items(1, "stored"));
        cache.publish(DOC, 3L, OptionalInt.empty(),
            items(DiagnosticsCache.MAX_ENTRIES_PER_DOCUMENT + 1, "x"));
        assertThat(cache.overflowed(DOC, 3L)).isTrue();
        cache.clear();
        assertThat(cache.snapshot(DOC, 3L)).isEmpty();
        assertThat(cache.overflowed(DOC, 3L)).isFalse();
    }
}
