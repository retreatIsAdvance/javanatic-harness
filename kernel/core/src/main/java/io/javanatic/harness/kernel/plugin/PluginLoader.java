package io.javanatic.harness.kernel.plugin;

import io.javanatic.harness.kernel.scope.Scope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 插件发现与装载。
 *
 * 发现与顺序分离：discover() 建立 id→Plugin 索引；加载顺序由调用方给出
 * （boot 流程按 rows 顺序，或 topoSort 按 requires 拓扑序）。
 * fail loud 三类：重复 id；requires 引用未先行加载或未发现的 id；依赖环。
 */
public final class PluginLoader {

    /**
     * 发现 module-path / classpath 上全部 Plugin，按 id 索引（保持发现顺序）。
     * 重复 id fail loud。
     *
     * @return id → Plugin（发现序）
     */
    public Map<String, Plugin> discover() {
        return index(ServiceLoader.load(Plugin.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList());
    }

    /**
     * 由插件序列建索引（discover 的纯逻辑部分，供测试直接驱动）。
     * 重复 id fail loud。
     *
     * @param discovered 已发现的插件序列
     * @return id → Plugin（输入序）
     */
    static Map<String, Plugin> index(Iterable<Plugin> discovered) {
        Map<String, Plugin> byId = new LinkedHashMap<>();
        for (Plugin p : discovered) {
            Plugin prev = byId.putIfAbsent(p.id(), p);
            if (prev != null) {
                throw new IllegalStateException(
                    "Duplicate plugin id '" + p.id() + "': "
                        + prev.getClass().getName() + " vs " + p.getClass().getName());
            }
        }
        return byId;
    }

    /**
     * 按给定顺序加载：每个 plugin 一个 {@link PluginScope} 挂载视图
     * （provide 落共享 root，effect/订阅落插件私有 child）。
     * requires 中出现尚未加载的 id → fail loud（顺序错了）；列表内重复 id → fail loud。
     * apply 抛异常 → 立即 close 该视图（回滚它已注册的全部副作用，含已 provide 的服务）→ 异常上抛。
     * 加载逐插件原子：不存在半挂载的插件（R3）。
     *
     * @param root 挂载根 scope（Runtime.root()）
     * @param ordered 加载顺序（boot rows 序或 topoSort 结果）
     */
    public void loadAll(Scope root, List<Plugin> ordered) {
        Set<String> loaded = new HashSet<>();
        for (Plugin p : ordered) {
            if (!loaded.add(p.id())) {
                throw new IllegalStateException("Plugin '" + p.id() + "' appears twice in load order");
            }
            for (String dep : p.requires()) {
                if (!loaded.contains(dep)) {
                    throw new IllegalStateException(
                        "Plugin '" + p.id() + "' requires '" + dep
                            + "' which is not loaded before it (check row order)");
                }
            }
            Scope mount = new PluginScope(root, root.child());
            try {
                p.apply(mount);
            } catch (Exception e) {
                mount.close();
                throw new IllegalStateException("Plugin failed and rolled back: " + p.id(), e);
            }
        }
    }

    /**
     * 按 requires 做 Kahn 拓扑排序。同层按输入序，输入确定则输出确定。
     * 依赖环或引用集合外的 id → fail loud（typo 或装配缺口）。
     *
     * @param plugins 待排序插件（可来自 discover().values()）
     * @return 拓扑序加载列表
     */
    public List<Plugin> topoSort(Collection<Plugin> plugins) {
        Map<String, Plugin> byId = index(plugins);
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (Plugin p : plugins) {
            pending.putIfAbsent(p.id(), 0);
        }
        for (Plugin p : plugins) {
            for (String dep : p.requires()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalStateException(
                        "Plugin '" + p.id() + "' requires unknown plugin '" + dep + "'");
                }
                pending.merge(p.id(), 1, Integer::sum);
            }
        }
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (Plugin p : plugins) {
            for (String dep : p.requires()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(p.id());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : pending.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<Plugin> sorted = new ArrayList<>(byId.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            sorted.add(byId.get(id));
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (pending.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (sorted.size() != byId.size()) {
            Set<String> remaining = new LinkedHashSet<>(byId.keySet());
            sorted.forEach(p -> remaining.remove(p.id()));
            throw new IllegalStateException("Plugin dependency cycle among: " + remaining);
        }
        return sorted;
    }
}
