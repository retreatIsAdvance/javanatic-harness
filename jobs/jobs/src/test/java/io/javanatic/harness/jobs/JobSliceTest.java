package io.javanatic.harness.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 切片游标不变量：区间非负、不越捕获上界；endOfCapture 区分读尽与截断。 */
class JobSliceTest {

    @Test
    void rejectsCursorBeyondCapture() {
        assertThatThrownBy(() -> new JobSlice("x", 11, 10, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounds");
        assertThatThrownBy(() -> new JobSlice("x", -1, 10, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobSlice(null, 0, 10, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryCursors() {
        assertThat(new JobSlice("", 0, 0, false).endOfCapture()).isTrue();
        assertThat(new JobSlice("abc", 3, 3, false).endOfCapture()).isTrue();
        assertThat(new JobSlice("ab", 2, 5, false).endOfCapture()).isFalse();
    }

    @Test
    void truncationFlagSurvivesAtEndOfCapture() {
        JobSlice slice = new JobSlice("abc", 3, 3, true);
        assertThat(slice.endOfCapture()).isTrue();
        assertThat(slice.outputTruncated()).isTrue();
    }
}
