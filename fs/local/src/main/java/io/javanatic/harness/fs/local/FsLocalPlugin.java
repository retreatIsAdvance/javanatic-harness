package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

/** 提供本地文件系统实现（id "fs-local"，Files.* 直包装，无沙箱）。 */
public final class FsLocalPlugin implements Plugin {

    @Override
    public String id() {
        return "fs-local";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(FsService.KEY, new LocalFs());
    }
}
