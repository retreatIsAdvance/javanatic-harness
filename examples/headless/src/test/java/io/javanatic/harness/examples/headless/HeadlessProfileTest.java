package io.javanatic.harness.examples.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** --profile 解析:存在文件直用 / 名字命中 home / 未知 fail loud。 */
class HeadlessProfileTest {

    @TempDir
    Path dir;

    @Test
    void existingFilePathWins() throws Exception {
        Path file = dir.resolve("my.yml");
        Files.writeString(file, "name: my\nbundles: []\nrows: []\n");
        assertThat(HeadlessMain.resolveProfile(file.toString())).isEqualTo(file);
    }

    @Test
    void nameResolvesUnderHarnessHome() throws Exception {
        String original = System.getProperty("user.home");
        Path home = dir.resolve("home");
        Path profileDir = home.resolve(".harness").resolve("profiles").resolve("prod");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("profile.yml"), "name: prod\nbundles: []\nrows: []\n");
        try {
            System.setProperty("user.home", home.toString());
            assertThat(HeadlessMain.resolveProfile("prod")).isEqualTo(profileDir.resolve("profile.yml"));
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @Test
    void unknownNameFailsLoud() {
        assertThatThrownBy(() -> HeadlessMain.resolveProfile("no-such-profile"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no-such-profile");
    }
}
