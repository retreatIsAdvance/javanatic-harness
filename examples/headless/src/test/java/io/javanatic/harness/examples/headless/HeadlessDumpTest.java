package io.javanatic.harness.examples.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * it36 停 1 --dump 消费面(T3 读面 CLI 腿):keyless 只读旁路,stdout =
 * AppBoot.dump(compose(boot)) 组合期行序(含禁用行注解、masked 脱敏);
 * 与任务文本/其余旁路互斥(exit 2 用法面)。post-boot 一致性——与
 * --verify 同段,不进 API key 检查、不建 agent。
 */
class HeadlessDumpTest {

    @TempDir
    Path workspace;

    @TempDir
    Path sessions;

    @Test
    void parseAcceptsDumpFlag() {
        assertThat(HeadlessMain.parse(new String[] {"--dump"}).dump()).isTrue();
        assertThat(HeadlessMain.parse(new String[] {}).dump()).isFalse();
    }

    @Test
    void dumpIsExclusiveWithTaskAndOtherBypasses() {
        String[][] conflicts = {
            {"--dump", "任务文本"},
            {"--dump", "--resume=s1"},
            {"--dump", "--verify"},
            {"--dump", "--sessions"},
            {"--dump", "--session=s1"},
            {"--dump", "--session=s1", "--session-view=turns"},
            {"--dump", "--workbench", "--session=s1"},
            {"--dump", "--serve"},
        };
        for (String[] args : conflicts) {
            assertThatThrownBy(() -> HeadlessMain.parse(args))
                .as("args=%s", String.join(" ", args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--dump");
        }
    }

    @Test
    void dumpPrintsCompositionRowsKeylessWithDisabledAnnotations() throws Exception {
        Result result = run("--dump");

        assertThat(result.exit()).isZero();
        assertThat(result.stdout())
            .contains("session-store")
            .contains("approval-auto")
            .contains("approval-ask")
            .contains("(disabled: true)")
            .contains("lsp-jdtls");
        // 组合期行序保留:bundle 首行 session-store 在 approval-auto 之前
        assertThat(result.stdout().indexOf("session-store"))
            .isLessThan(result.stdout().indexOf("approval-auto"));
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void dumpMasksSensitiveConfigValues() throws Exception {
        Path profile = workspace.resolve("dump-mask.yml");
        Files.writeString(profile, """
            name: dump-mask
            policy: standard
            bundles: [base]
            rows:
              - plugin: llm-openai-compat
                replace: true
                config:
                  name: deepseek
                  apiKey: sk-dump-secret
            """);

        Result result = run("--dump", "--profile=" + profile);

        assertThat(result.exit()).isZero();
        assertThat(result.stdout()).contains("****").doesNotContain("sk-dump-secret");
    }

    @Test
    void usageMentionsDump() {
        assertThat(HeadlessMain.USAGE).contains("--dump");
    }

    private Result run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = HeadlessMain.run(HeadlessMain.parse(args), workspace, sessions,
            new BufferedReader(new StringReader("")),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String stdout, String stderr) {
    }
}
