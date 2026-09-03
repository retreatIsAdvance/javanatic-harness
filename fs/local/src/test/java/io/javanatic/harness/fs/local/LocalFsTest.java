package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地实现直测：读写改删列 + fail loud 契约。 */
class LocalFsTest {

    @TempDir
    Path dir;

    private final FsService fs = new LocalFs();

    @Test
    void writeCreatesParentsReadRoundTrips() throws IOException {
        Path file = dir.resolve("a/b/c.txt");
        fs.write(file, "hello");
        assertThat(fs.read(file)).isEqualTo("hello");
    }

    @Test
    void editReplacesFirstOccurrenceOnly() throws IOException {
        Path file = dir.resolve("f.txt");
        fs.write(file, "x old x old");
        String edited = fs.edit(file, "old", "new");
        assertThat(edited).isEqualTo("x new x old");
        assertThat(fs.read(file)).isEqualTo("x new x old");
    }

    @Test
    void editMissingOldStringFailsLoud() throws IOException {
        Path file = dir.resolve("f.txt");
        fs.write(file, "content");
        assertThatThrownBy(() -> fs.edit(file, "absent", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oldString not found");
    }

    @Test
    void listIsSortedByNameWithType() throws IOException {
        fs.write(dir.resolve("b.txt"), "1");
        fs.write(dir.resolve("a.txt"), "2");
        Files.createDirectory(dir.resolve("zdir"));
        assertThat(fs.list(dir))
            .extracting(FsService.DirEntry::name)
            .containsExactly("a.txt", "b.txt", "zdir");
        assertThat(fs.list(dir).getLast().directory()).isTrue();
    }

    @Test
    void deleteMissingFileFailsLoud() {
        assertThatThrownBy(() -> fs.delete(dir.resolve("ghost")))
            .isInstanceOf(IOException.class);
    }
}
