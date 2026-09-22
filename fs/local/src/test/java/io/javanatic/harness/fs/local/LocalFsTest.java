package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void editReplacesUniqueMatch() throws IOException {
        Path file = dir.resolve("f.txt");
        fs().write(file, "x old z");
        String edited = fs().edit(file, "old", "new");
        assertThat(edited).isEqualTo("x new z");
        assertThat(fs().read(file)).isEqualTo("x new z");
    }

    // ===== 唯一匹配（it21）：多处即拒，不静默改第一处 =====

    @Test
    void editRejectsAmbiguousMatchWithCountAndLines() throws IOException {
        Path file = dir.resolve("dup.txt");
        fs().write(file, "old a\nb\nold c\nold");
        assertThatThrownBy(() -> fs().edit(file, "old", "new"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oldString is not unique")
            .hasMessageContaining("3 occurrences")
            .hasMessageContaining("lines 1, 3, 4");
        assertThat(Files.readString(file)).isEqualTo("old a\nb\nold c\nold");   // 拒而未写
    }

    @Test
    void editAmbiguityReportsSameLineOnce() throws IOException {
        Path file = dir.resolve("same-line.txt");
        fs().write(file, "old old");
        assertThatThrownBy(() -> fs().edit(file, "old", "new"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2 occurrences")
            .hasMessageContaining("lines 1)");
    }

    @Test
    void editAmbiguityLineListIsBounded() throws IOException {
        Path file = dir.resolve("many.txt");
        fs().write(file, "same\n".repeat(25));
        assertThatThrownBy(() -> fs().edit(file, "same", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("25 occurrences")
            .hasMessageContaining("lines 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, …");
    }

    @Test
    void editEmptyOldStringFailsLoud() throws IOException {
        Path file = dir.resolve("f.txt");
        fs().write(file, "content");
        assertThatThrownBy(() -> fs().edit(file, "", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oldString must not be empty");
        assertThat(Files.readString(file)).isEqualTo("content");   // 拒而未写
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
        FsService.Listing listing = fs().list(dir);
        assertThat(listing.truncated()).isFalse();
        assertThat(listing.entries())
            .extracting(FsService.DirEntry::name)
            .containsExactly("a.txt", "b.txt", "zdir");
        assertThat(listing.entries().getLast().directory()).isTrue();
    }

    // ===== 有界化（it20）：字节/条目上限 + 截断标记 + 编辑 fail loud =====

    @Test
    void readTruncatesAtByteCapWithMarker() throws IOException {
        Path file = dir.resolve("big.txt");
        Files.writeString(file, "0123456789");
        assertThat(new LocalFs(dir, 8, 1000).read(file))
            .isEqualTo("01234567" + FsService.READ_TRUNCATED_MARKER);
    }

    @Test
    void readAtCapExactlyIsNotTruncated() throws IOException {
        Path file = dir.resolve("exact.txt");
        Files.writeString(file, "12345678");
        assertThat(new LocalFs(dir, 8, 1000).read(file)).isEqualTo("12345678");
    }

    @Test
    void readTruncationKeepsMultiByteCharacterWhole() throws IOException {
        Path file = dir.resolve("cjk.txt");
        Files.writeString(file, "你你你");   // 每个「你」3 字节
        // 5 字节截断落在第二个「你」中间：整字符回退，不留半字符
        assertThat(new LocalFs(dir, 5, 1000).read(file))
            .isEqualTo("你" + FsService.READ_TRUNCATED_MARKER);
        // 6 字节：恰好两个完整字符
        assertThat(new LocalFs(dir, 6, 1000).read(file))
            .isEqualTo("你你" + FsService.READ_TRUNCATED_MARKER);
    }

    @Test
    void editOverReadCapFailsLoudWithSizeAndCap() throws IOException {
        Path file = dir.resolve("big.txt");
        Files.writeString(file, "0123456789");
        assertThatThrownBy(() -> new LocalFs(dir, 4, 1000).edit(file, "0123", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("file too large to edit")
            .hasMessageContaining("10 bytes")
            .hasMessageContaining("maxReadBytes 4");
        assertThat(Files.readString(file)).isEqualTo("0123456789");   // 拒而未写
    }

    @Test
    void listTruncatesAtCapAndFlagsIt() throws IOException {
        for (String name : List.of("a.txt", "b.txt", "c.txt", "d.txt")) {
            fs().write(dir.resolve(name), "x");
        }
        FsService.Listing capped = new LocalFs(dir, 1000, 3).list(dir);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.entries()).extracting(FsService.DirEntry::name)
            .containsExactly("a.txt", "b.txt", "c.txt");
        assertThat(new LocalFs(dir, 1000, 4).list(dir).truncated()).isFalse();
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
