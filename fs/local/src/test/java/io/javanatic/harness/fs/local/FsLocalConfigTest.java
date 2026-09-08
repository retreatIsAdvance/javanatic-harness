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
    void missingRootFailsLoudWithoutImplicitDefault() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt, List.of(new FsLocalPlugin())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("config missing required 'root' for plugin 'fs-local'");
        }
    }
}
