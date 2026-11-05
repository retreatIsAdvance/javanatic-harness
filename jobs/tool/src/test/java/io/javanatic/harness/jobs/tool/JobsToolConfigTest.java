package io.javanatic.harness.jobs.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** jobs-tool 行配置（B31）：单键上限正数校验 + 缺省。 */
class JobsToolConfigTest {

    @Test
    void defaultsApply() {
        assertThat(JobsToolConfig.defaults().maxReadBytes()).isEqualTo(262144L);
    }

    @Test
    void parsesOverride() {
        assertThat(JobsToolConfig.parse(Map.of("maxReadBytes", 4096L), "jobs-tool").maxReadBytes())
                .isEqualTo(4096L);
    }

    @Test
    void rejectsNonPositive() {
        assertThatThrownBy(() -> new JobsToolConfig(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxReadBytes");
        assertThatThrownBy(() -> new JobsToolConfig(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
