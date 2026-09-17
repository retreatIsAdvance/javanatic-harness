package io.javanatic.harness.examples.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 参数解析/默认值/key 优先级 + verify 行为不变。 */
class HeadlessOptionsTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void defaultsAreDeepSeek() {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {"任务"});
        assertThat(options.provider()).isEqualTo("deepseek");
        assertThat(options.model()).isEqualTo("deepseek-chat");
        assertThat(options.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(options.apiKeyEnv()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(options.apiKeyLiteral()).isNull();
    }

    @Test
    void customVendorFlagsParse() {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {
            "任务", "--provider=kimi", "--model=kimi-k2",
            "--base-url=https://api.moonshot.cn/v1", "--api-key-env=MOONSHOT_KEY"});
        assertThat(options.provider()).isEqualTo("kimi");
        assertThat(options.model()).isEqualTo("kimi-k2");
        assertThat(options.baseUrl()).isEqualTo("https://api.moonshot.cn/v1");
        assertThat(options.apiKeyEnv()).isEqualTo("MOONSHOT_KEY");
    }

    @Test
    void literalKeyWinsOverEnv() {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            new String[] {"t", "--api-key=literal-key"});
        assertThat(options.resolvedApiKey()).isEqualTo("literal-key");
    }

    @Test
    void missingEnvYieldsNullKey() {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            new String[] {"t", "--api-key-env=JH_DEFINITELY_UNSET_VAR"});
        assertThat(options.resolvedApiKey()).isNull();
    }

    @Test
    void unknownFlagAndSecondTaskFailLoud() {
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"t", "--wat"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--wat");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"a", "b"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("只能有一个");
    }

    @Test
    void helpFlagParsesAndUsageListsContract() {
        assertThat(HeadlessMain.parse(new String[] {"--help"}).help()).isTrue();
        assertThat(HeadlessMain.parse(new String[] {"-h"}).help()).isTrue();
        assertThat(HeadlessMain.USAGE).contains(
            "--workspace=", "--approval=auto|ask|deny", "--budget=", "--docker", "--resume=",
            "--profile=", "--policy=", "--verify");
    }

    @Test
    void workspaceFlagRequiresExistingDirectory() {
        assertThat(HeadlessMain.parse(new String[] {"t", "--workspace=" + workspace}).workspace())
            .isEqualTo(workspace.toAbsolutePath().normalize());
        assertThatThrownBy(() -> HeadlessMain.parse(
            new String[] {"t", "--workspace=" + workspace.resolve("missing")}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--workspace");
    }

    @Test
    void approvalFlagValidatesVocabulary() {
        assertThat(HeadlessMain.parse(new String[] {"t", "--approval=ask"}).approval()).isEqualTo("ask");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"t", "--approval=wat"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("auto|ask|deny");
    }

    @Test
    void budgetFlagParsesPositiveAndDefaultsToUnlimited() {
        assertThat(HeadlessMain.parse(new String[] {"t", "--budget=50000"}).budget()).isEqualTo(50000L);
        assertThat(HeadlessMain.parse(new String[] {"t"}).budget()).isZero();
    }

    @Test
    void budgetFlagRejectsNonPositiveAndMalformed() {
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"t", "--budget=0"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--budget");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"t", "--budget=-5"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--budget");
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"t", "--budget=wat"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--budget");
    }

    @Test
    void imageFlagRequiresDocker() {
        assertThatThrownBy(() -> HeadlessMain.parse(new String[] {"t", "--image=ubuntu:24.04"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--docker");
    }

    @Test
    void runSessionIdsAreTimestampedAndUnique() {
        String first = HeadlessMain.newRunSessionId();
        String second = HeadlessMain.newRunSessionId();
        assertThat(first).matches("headless-\\d+-[0-9a-f]{1,4}");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void verifyStillRunsWithoutKey() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(new String[] {"--verify"});
        assertThat(HeadlessMain.run(options, workspace, sessions)).isZero();
    }

    @Test
    void verifyProductionStillRejectsAuto() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            new String[] {"--verify", "--policy=PRODUCTION"});
        assertThat(HeadlessMain.run(options, workspace, sessions)).isEqualTo(1);
    }

    @Test
    void taskWithoutKeyFailsLoud() throws Exception {
        HeadlessMain.RunnerOptions options = HeadlessMain.parse(
            new String[] {"t", "--api-key-env=JH_DEFINITELY_UNSET_VAR"});
        assertThat(HeadlessMain.run(options, workspace, sessions)).isEqualTo(2);
    }
}
