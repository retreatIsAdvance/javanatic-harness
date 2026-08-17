package io.javanatic.harness.kernel.plugin;

import io.javanatic.harness.kernel.scope.Scope;

import java.util.Set;

/**
 * 带稳定 id 的挂载单元。id 是配置 rows / patch / 依赖声明的引用锚点——
 * rows 按 id 引用插件，不写类名（类名在 JPMS 下不可跨模块反射访问）。
 */
public interface Plugin {

    /**
     * 全局唯一的稳定 id（kebab-case，如 "llm-deepseek"、"fs-local"）。
     *
     * @return 插件 id
     */
    String id();

    /**
     * 依赖的其他插件 id（决定加载顺序，拓扑排序用）。
     *
     * @return 依赖 id 集合，无依赖时为空
     */
    default Set<String> requires() {
        return Set.of();
    }

    /**
     * 挂载到自己的子 scope。所有注册（provide / events().on）都过 scope effect 栈，
     * 卸载时 LIFO 回收。禁止持有全局静态状态（R3 边界）。
     *
     * @param scope 本插件的专属子 scope
     */
    void apply(Scope scope) throws Exception;
}
