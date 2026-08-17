package io.javanatic.harness.kernel.scope;

import io.javanatic.harness.kernel.events.Events;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 顶层运行时：root scope + 事件总线 + 虚拟线程 executor。try-with-resources 入口。
 *
 * close 顺序：root scope LIFO 级联回收（全部插件 effect、子 scope、订阅），
 * 最后关 executor（等残余 NOTIFY listener 任务结束）。
 */
public final class Runtime implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Runtime.class.getName());

    private final Events events;
    private final ExecutorService virtualThreads;
    private final ScopeImpl root;

    public Runtime() {
        this.virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        this.events = new Events(virtualThreads);
        this.root = new ScopeImpl(null, this);
    }

    /** root scope：所有插件与子 scope 的挂载点。 */
    public Scope root() {
        return root;
    }

    /**
     * 为插件装载创建挂载视图（PluginScope，.scope 包内核装配）：
     * provide 落共享 root（跨插件可见），effect/订阅落插件私有 child
     * （close 即整体回滚）。PluginLoader.loadAll 逐插件调用。
     *
     * @return 挂载视图
     */
    public Scope mountScope() {
        return new PluginScope(root, root.child());
    }

    /**
     * 全局事件总线。订阅请优先走 {@link Scope#events()}（随 scope 回收）；
     * 本方法用于派发与进程级全局订阅。
     *
     * @return 事件总线
     */
    public Events events() {
        return events;
    }

    /** effect 回收失败的统一日志出口（回收不中断，继续排水）。 */
    void logDisposeError(Scope scope, Exception e) {
        LOG.log(System.Logger.Level.WARNING, "effect dispose failed in " + scope, e);
    }

    @Override
    public void close() {
        root.close();
        virtualThreads.close();
    }
}
