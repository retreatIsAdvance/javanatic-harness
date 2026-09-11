package io.javanatic.harness.sandbox.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 模式即规范化 allow-list 的<b>单一来源</b>：workspace-write = workspace 根 +
 * 宿主 /tmp + 平台临时目录。Seatbelt profile（sandbox-local）与进程内 fs 围栏
 * （fs-tool）都从这里取授权——两者永不漂移（「bash 能写而写工具不能」的
 * 不对称不可能出现）。
 *
 * <p>规范化（realpath）：Seatbelt 过滤与围栏 containment 都按解析后路径比较——
 * darwin 上 /tmp 即 /private/tmp，按拼写授予会匹配不到。解析失败（路径缺失）
 * 保守返回原拼写：缺失的根在其存在前匹配不到任何东西。
 */
public final class WritableRoots {

    private WritableRoots() {
    }

    /**
     * @param policy 文件效果策略
     * @return 规范化去重后的可写根；READ_ONLY 恒为空
     */
    public static Set<Path> of(SandboxPolicy policy) {
        Set<Path> roots = new LinkedHashSet<>();
        if (policy.mode() != SandboxMode.WORKSPACE_WRITE) {
            return roots;
        }
        roots.add(canonical(policy.workspaceRoot()));
        // 平台临时目录恒授予；宿主 /tmp 仅非 Windows——Windows 上 "/tmp" 是
        // 盘符相对路径，盘根下若出现同名目录即成真实授予（未承诺的放行）
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            roots.add(canonical(Path.of("/tmp")));
        }
        roots.add(canonical(Path.of(System.getProperty("java.io.tmpdir"))));
        return roots;
    }

    /** realpath 规范化；解析失败保守返回原拼写（见类注）。 */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missingOrUnreadable) {
            return path;
        }
    }
}
