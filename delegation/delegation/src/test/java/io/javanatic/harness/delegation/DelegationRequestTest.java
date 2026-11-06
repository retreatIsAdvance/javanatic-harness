package io.javanatic.harness.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 启动请求装配期校验（跨平台绝对路径夹具用 user.dir——pits 纪律）。 */
class DelegationRequestTest {

    private static final Path DIR = Path.of(System.getProperty("user.dir"));

    @Test
    void rejectsBlankTask() {
        assertThatThrownBy(() -> new DelegationRequest(" ", DIR, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
    }

    @Test
    void rejectsRelativeWorkingDirectory() {
        assertThatThrownBy(() -> new DelegationRequest("查一下", Path.of("rel"), 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsNonPositiveBudget() {
        assertThatThrownBy(() -> new DelegationRequest("查一下", DIR, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetTokens");
    }

    @Test
    void acceptsValidRequest() {
        DelegationRequest request = new DelegationRequest("并行检索三个模块并汇总", DIR, 5000);
        assertThat(request.task()).contains("检索");
        assertThat(request.budgetTokens()).isEqualTo(5000);
    }
}
