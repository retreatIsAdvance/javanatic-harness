package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.scope.Scope;

import java.nio.file.Path;
import java.util.Map;

/** 提供本地文件系统实现（id "fs-local"，Files.* 直包装，根目录限制强制在此）。 */
public final class FsLocalPlugin implements Plugin {

    private final LocalFs explicit;

    /** 数据组合路径：root 从行配置解析——安全边界无默认，缺失 fail loud。 */
    public FsLocalPlugin() {
        this.explicit = null;
    }

    /** @param root 工作区根(绝对路径;程序化组合的显式选择) */
    public FsLocalPlugin(Path root) {
        this.explicit = new LocalFs(root);
    }

    @Override
    public String id() {
        return "fs-local";
    }

    @Override
    public void apply(Scope scope) {
        LocalFs fs = explicit;
        if (fs == null) {
            Map<String, Object> config = scope.require(ConfigService.KEY).configFor(id());
            String root = ConfigValues.requireString(config, id(), "root");
            fs = new LocalFs(Path.of(root),
                ConfigValues.longValue(config, id(), "maxReadBytes", LocalFs.DEFAULT_MAX_READ_BYTES),
                (int) ConfigValues.longValue(config, id(), "maxListEntries",
                    LocalFs.DEFAULT_MAX_LIST_ENTRIES),
                (int) ConfigValues.longValue(config, id(), "searchMaxMatches",
                    LocalFs.DEFAULT_MAX_SEARCH_MATCHES));
        }
        scope.provide(FsService.KEY, fs);
    }
}
