package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 本地实现直测：读写改删列 + fail loud 契约 + 根目录边界（词法越界 + 真实路径围栏）。 */
class LocalFsTest {

    @TempDir
    Path dir;

    @TempDir
    Path outside;

    @TempDir
    Path store;

    private LocalFs fs() {
        return new LocalFs(dir);
    }

    @Test
    void writeCreatesParentsReadRoundTrips() throws IOException {
        Path file = dir.resolve("a/b/c.txt");
        fs().write(file, "hello");
        assertThat(fs().read(file)).isEqualTo("hello");
    }

    @Test
    void relativePathResolvesAgainstRoot() throws IOException {
        fs().write(Path.of("rel.txt"), "相对");
        assertThat(fs().read(Path.of("rel.txt"))).isEqualTo("相对");
        assertThat(Files.readString(dir.resolve("rel.txt"))).isEqualTo("相对");
    }

    @Test
    void absolutePathOutsideRootFailsLoud() {
        assertThatThrownBy(() -> fs().read(Path.of("/etc/hosts")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
    }

    @Test
    void dotDotEscapeFailsLoud() {
        assertThatThrownBy(() -> fs().read(Path.of("../outside.txt")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
    }

    @Test
    void editReplacesFirstOccurrenceOnly() throws IOException {
        Path file = dir.resolve("f.txt");
        fs().write(file, "x old x old");
        String edited = fs().edit(file, "old", "new");
        assertThat(edited).isEqualTo("x new x old");
        assertThat(fs().read(file)).isEqualTo("x new x old");
    }

    @Test
    void editMissingOldStringFailsLoud() throws IOException {
        Path file = dir.resolve("f.txt");
        fs().write(file, "content");
        assertThatThrownBy(() -> fs().edit(file, "absent", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oldString not found");
    }

    @Test
    void listIsSortedByNameWithType() throws IOException {
        fs().write(dir.resolve("b.txt"), "1");
        fs().write(dir.resolve("a.txt"), "2");
        Files.createDirectory(dir.resolve("zdir"));
        assertThat(fs().list(dir))
            .extracting(FsService.DirEntry::name)
            .containsExactly("a.txt", "b.txt", "zdir");
        assertThat(fs().list(dir).getLast().directory()).isTrue();
    }

    @Test
    void deleteMissingFileFailsLoud() {
        assertThatThrownBy(() -> fs().delete(dir.resolve("ghost")))
            .isInstanceOf(IOException.class);
    }

    // ===== 真实路径围栏（it12.6）：符号链接在判定前被展开 =====

    @Test
    void symlinkToOutsideIsRejectedForReadAndWrite() throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(dir.resolve("esc-file"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(dir.resolve("esc-dir"), outside);
        assertThatThrownBy(() -> fs().read(dir.resolve("esc-file")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
        assertThatThrownBy(() -> fs().read(dir.resolve("esc-dir/secret.txt")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
        assertThatThrownBy(() -> fs().write(dir.resolve("esc-dir/new.txt"), "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
        assertThat(outside.resolve("new.txt")).doesNotExist();
        assertThatThrownBy(() -> fs().delete(dir.resolve("esc-file")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
        assertThat(dir.resolve("esc-file")).exists();   // 拒而未删：链接本身也不动
    }

    @Test
    void danglingSymlinkFailsLoudAndDoesNotCreateTargetOutside() throws IOException {
        Path link = Files.createSymbolicLink(dir.resolve("dangling"), outside.resolve("not-yet.txt"));
        assertThatThrownBy(() -> fs().write(link, "x"))
            .isInstanceOf(IOException.class);
        assertThat(outside.resolve("not-yet.txt")).doesNotExist();
    }

    @Test
    void symlinkToInsideRootIsAllowed() throws IOException {
        fs().write(Path.of("sub/target.txt"), "内");
        Files.createSymbolicLink(dir.resolve("alias-dir"), dir.resolve("sub"));
        Files.createSymbolicLink(dir.resolve("alias-file"), dir.resolve("sub/target.txt"));
        fs().write(Path.of("alias-dir/via-link.txt"), "经链接写入");
        assertThat(fs().read(Path.of("sub/via-link.txt"))).isEqualTo("经链接写入");
        assertThat(fs().read(Path.of("alias-file"))).isEqualTo("内");
    }

    @Test
    void rootSymlinkAliasNormalizesAndAliasAccessIsAllowed() throws IOException {
        Path real = Files.createDirectories(store.resolve("real-root"));
        Files.writeString(real.resolve("f.txt"), "v");
        Path alias = Files.createSymbolicLink(store.resolve("alias-root"), real);
        LocalFs viaAlias = new LocalFs(alias);
        assertThat(viaAlias.read(Path.of("f.txt"))).isEqualTo("v");
        assertThat(viaAlias.read(real.resolve("f.txt"))).isEqualTo("v");
        assertThat(viaAlias.read(alias.resolve("f.txt"))).isEqualTo("v");
        viaAlias.write(Path.of("g.txt"), "w");
        assertThat(Files.readString(real.resolve("g.txt"))).isEqualTo("w");
    }

    @Test
    void missingRootFailsLoudAtConstruction() {
        assertThatThrownBy(() -> new LocalFs(dir.resolve("nope")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("root must exist");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void darwinTmpAliasNormalizesIntoFence() throws IOException {
        assumeTrue(Files.isSymbolicLink(Path.of("/tmp")), "/tmp 非符号链接，别名归一场景不成立");
        LocalFs tmpFs = new LocalFs(Path.of("/tmp"));
        Path probe = Files.createTempFile(Path.of("/tmp"), "jh-fs-fence-", ".txt");
        try {
            assertThat(tmpFs.read(probe)).isEmpty();
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
