package io.javanatic.harness.agentloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 项目说明装载：整读、哈希、有界截断（换行收尾）与缺失 fail loud 的边界。 */
class InstructionsFileTest {

    @TempDir
    Path dir;

    @Test
    void readsContentAndKeylessSha256() throws IOException {
        Path file = dir.resolve("AGENTS.md");
        Files.writeString(file, "abc");

        InstructionsFile.Loaded loaded = InstructionsFile.read(file);

        assertThat(loaded.content()).isEqualTo("abc");
        assertThat(loaded.truncated()).isFalse();
        // NIST 向量：SHA-256("abc")——哈希口径钉住，不随实现漂移
        assertThat(InstructionsFile.sha256(loaded.content()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void missingFileFailsLoudForCallerToDecide() {
        assertThatThrownBy(() -> InstructionsFile.read(dir.resolve("ghost.md")))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void exactCapIsNotTruncated() throws IOException {
        Path file = dir.resolve("exact.md");
        Files.writeString(file, "x".repeat(InstructionsFile.MAX_BYTES));

        InstructionsFile.Loaded loaded = InstructionsFile.read(file);

        assertThat(loaded.truncated()).isFalse();
        assertThat(loaded.content()).hasSize(InstructionsFile.MAX_BYTES);
    }

    @Test
    void overCapTruncatesAtLastNewline() throws IOException {
        Path file = dir.resolve("big.md");
        String line = "y".repeat(99) + "\n";   // 100 字节整行
        Files.writeString(file, line.repeat(700));   // 70000 字节 > 64 KiB

        InstructionsFile.Loaded loaded = InstructionsFile.read(file);

        assertThat(loaded.truncated()).isTrue();
        assertThat(loaded.content()).endsWith("\n");
        assertThat(loaded.content().lines().count()).isPositive();
        assertThat(loaded.content().chars().filter(c -> c == '\n').count())
            .isEqualTo(loaded.content().lines().count());   // 无半行
        assertThat(loaded.content().getBytes(StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(InstructionsFile.MAX_BYTES);
    }

    @Test
    void overCapWithoutNewlineKeepsBytePrefix() throws IOException {
        Path file = dir.resolve("one-line.md");
        Files.writeString(file, "z".repeat(InstructionsFile.MAX_BYTES + 10));

        InstructionsFile.Loaded loaded = InstructionsFile.read(file);

        assertThat(loaded.truncated()).isTrue();
        assertThat(loaded.content()).hasSize(InstructionsFile.MAX_BYTES);
    }
}
