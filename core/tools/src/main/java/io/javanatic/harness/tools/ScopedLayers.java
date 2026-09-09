package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * per-scope overlay 注册表(06 §3)。root(组合层)+ 每个作用域一个层;
 * walk 沿父链 nearest-first 收集,合并方按 root-first 应用即得 shadowing
 * (最近 scope 同名覆盖)。层随 owner scope 关闭回收(注册即 effect,R3)。
 *
 * @param <L> 层类型
 */
public final class ScopedLayers<L> {

    private final L rootLayer;
    private final ConcurrentMap<Scope, L> scoped = new ConcurrentHashMap<>();
    private final Supplier<L> layerFactory;

    /** @param layerFactory 层工厂 */
    public ScopedLayers(Supplier<L> layerFactory) {
        this.layerFactory = layerFactory;
        this.rootLayer = layerFactory.get();
    }

    /** 组合层(全局)。 */
    public L root() {
        return rootLayer;
    }

    /**
     * 本 scope 的层(不存在则创建;创建时在 owner 上登记关闭回收)。
     *
     * @param scope 层主 scope
     * @return 层
     */
    public L of(Scope scope) {
        if (scope == null) {
            return rootLayer;
        }
        L created = layerFactory.get();
        L layer = scoped.putIfAbsent(scope, created);
        if (layer != null) {
            return layer;
        }
        // 层随 owner 关闭整体回收——注册是 effect(引用式判等防误删后来者同名层)
        scope.onClose(() -> scoped.remove(scope, created));
        return created;
    }

    /**
     * 沿父链收集已存在的层,nearest-first(本 scope 在前,root 最后)。
     *
     * @param scope 查询起点
     * @return 命中的层(空链返回空列表;root 层恒在末位——组合层注册对每个 agent 可见)
     */
    public List<L> walk(Scope scope) {
        List<L> hits = new ArrayList<>();
        for (Scope cursor = scope; cursor != null; cursor = cursor.parent()) {
            L layer = scoped.get(cursor);
            if (layer != null) {
                hits.add(layer);
            }
        }
        hits.add(rootLayer);
        return hits;
    }
}
