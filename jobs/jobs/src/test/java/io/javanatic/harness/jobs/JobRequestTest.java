package io.javanatic.harness.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 启动请求装配期校验：坏请求在此失败，不进 Provider。 */
class JobRequestTest {

    private static final Path DIR = Path.of("/tmp");

    @Test
    void rejectsBlankCommand() {
        assertThatThrownBy(() -> new JobRequest("  ", DIR, Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void rejectsNullCommand() {
        assertThatThrownBy(() -> new JobRequest(null, DIR, Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRelativeWorkingDirectory() {
        assertThatThrownBy(() -> new JobRequest("mvn -q package", Path.of("rel"), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new JobRequest("mvn -q package", DIR, Duration.ZERO, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> new JobRequest("mvn -q package", DIR, null, 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveOutputCap() {
        assertThatThrownBy(() -> new JobRequest("mvn -q package", DIR, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputCapBytes");
    }

    @Test
    void acceptsValidRequest() {
        JobRequest request = new JobRequest("mvn -q package", DIR, Duration.ofMinutes(30), 262144);
        assertThat(request.command()).isEqualTo("mvn -q package");
        assertThat(request.outputCapBytes()).isEqualTo(262144);
    }
}
