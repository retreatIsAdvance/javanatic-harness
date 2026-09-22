package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据组合路径:无参构造经 ConfigService 取 root;缺失 fail loud(安全边界无默认)。 */
class FsLocalConfigTest {

    @TempDir
    Path root;

    @Test
    void noArgConstructorResolvesRootFromConfig() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "fs-local".equals(id)
                ? Map.of("root", root.toString()) : Map.of());
            new PluginLoader().loadAll(rt, List.of(new FsLocalPlugin()));
            FsService fs = rt.root().require(FsService.KEY);
            fs.write(Path.of("in-root.txt"), "ok");
            assertThatThrownBy(() -> fs.read(Path.of("/etc/hosts")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes workspace root");
        }
    }

    @Test
    void quotaKeysResolveFromConfig() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "fs-local".equals(id)
                ? Map.of("root", root.toString(), "maxReadBytes", 4L, "maxListEntries", 1L,
                    "searchMaxMatches", 1L)
                : Map.of());
            new PluginLoader().loadAll(rt, List.of(new FsLocalPlugin()));
            FsService fs = rt.root().require(FsService.KEY);
            fs.write(Path.of("note.txt"), "hello");
            assertThat(fs.read(Path.of("note.txt")))
                .isEqualTo("hell" + FsService.READ_TRUNCATED_MARKER);
            fs.write(Path.of("b.txt"), "2");
            FsService.Listing listing = fs.list(Path.of("."));
            assertThat(listing.truncated()).isTrue();
            assertThat(listing.entries()).hasSize(1);

            // searchMaxMatches=1：两个命中只留序最小者，截断位显形
            fs.write(Path.of("a.txt"), "hi");
            fs.write(Path.of("b.txt"), "hi");
            FsService.SearchResult found = fs.search("hi", Path.of("."));
            assertThat(found.truncated()).isTrue();
            assertThat(found.matches()).extracting(FsService.Match::path)
                .containsExactly("a.txt");
        }
    }

    @Test
    void missingRootFailsLoudWithoutImplicitDefault() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(new FsLocalPlugin())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("config missing required 'root' for plugin 'fs-local'");
        }
    }
}
