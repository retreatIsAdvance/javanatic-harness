package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

import java.nio.file.Path;

/** 提供本地文件系统实现（id "fs-local"，Files.* 直包装，根目录限制强制在此）。 */
public final class FsLocalPlugin implements Plugin {

    private final LocalFs fs;

    /** @param root 工作区根(绝对路径;组合期决定 agent 可触达的文件范围) */
    public FsLocalPlugin(Path root) {
        this.fs = new LocalFs(root);
    }

    @Override
    public String id() {
        return "fs-local";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(FsService.KEY, fs);
    }
}
