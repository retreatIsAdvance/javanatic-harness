package io.javanatic.harness.kernel.events;

import io.javanatic.harness.kernel.scope.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件总线：订阅 + 两模式派发。
 *
 * NOTIFY 族三个方法：notify（每 listener 一个虚拟线程，尽力而为）、
 * notifyOrdered（顺序派发，异常传播）、notifyAndWait（并发派发并 join 全部完成，
 * 持久化 flush barrier 用）。
 * WATERFALL 族两个方法：waterfall（中间件链）、firstOf（查询，替代 cordis bail）。
 * 模式与方法由 {@link EventKey} 的 requireMode 配对把关，错配 fail loud。
 *
 * 订阅请经 {@link #forScope(Scope)}（挂 scope effect 栈，随 scope 回收）或
 * {@link #forMount(Scope, Scope)}（插件挂载视图）；本类的 subscribe* 方法仅供内核内部接线。
 */
public final class Events {

    private static final System.Logger LOG = System.getLogger(Events.class.getName());

    private final ConcurrentHashMap<EventKey<?>, CopyOnWriteArrayList<Registration<?>>> hooks = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreads;

    /** 订阅条目：listener + 订阅 scope（null = 全局订阅，忽略 scope 过滤）。 */
    private record Registration<T>(Object listener, Scope scope) {}

    /**
     * @param virtualThreads NOTIFY 族并发派发的执行器（Runtime 持有的虚拟线程 executor）
     */
    public Events(ExecutorService virtualThreads) {
        this.virtualThreads = virtualThreads;
    }

    /**
     * 返回绑定 scope 的订阅视图（{@code Scope.events()} 的实现后端）：
     * 订阅挂 scope effect 栈，随 scope 回收。
     *
     * @param scope 订阅绑定的 scope
     * @return 订阅视图
     */
    public ScopedEvents forScope(Scope scope) {
        return new ScopedEventsImpl(this, scope);
    }

    /**
     * 返回插件挂载视图（{@code Runtime.mountScope()} 的订阅后端）：
     * 过滤绑 shared（收到整个挂载子树——含 session 层——派发的事件），
     * 注销登记在 owner（插件私有栈，close 即退订）。
     *
     * @param shared 过滤绑定的共享 scope（root）
     * @param owner  注销登记的插件私有 scope
     * @return 订阅视图
     */
    public ScopedEvents forMount(Scope shared, Scope owner) {
        return new ScopedEventsImpl(this, shared, owner);
    }

    // ────────── 订阅 ──────────

    <T> AutoCloseable subscribeNotify(EventKey<T> key, EventListener<? super T> listener, Scope bound) {
        key.requireMode(EventKey.Mode.NOTIFY);
        return addRegistration(key, listener, bound);
    }

    <T> AutoCloseable subscribeWaterfall(EventKey<T> key, WaterfallListener<? super T> listener, Scope bound) {
        key.requireMode(EventKey.Mode.WATERFALL);
        return addRegistration(key, listener, bound);
    }

    private AutoCloseable addRegistration(EventKey<?> key, Object listener, Scope bound) {
        Registration<?> reg = new Registration<>(listener, bound);
        CopyOnWriteArrayList<Registration<?>> list = hooks.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(reg);
        return () -> list.remove(reg);
    }

    // ────────── NOTIFY ──────────

    /**
     * 并发通知：每 listener 一个虚拟线程，fire-and-forget；异常记日志，不传播。
     *
     * @param <T> 负载类型
     * @param key NOTIFY key
     * @param origin 派发方 scope（订阅 scope 过滤的依据）
     * @param carrier 派发方对象，透传给监听器
     * @param payload 事件负载
     */
    public <T> void notify(EventKey<T> key, Scope origin, Object carrier, T payload) {
        key.requireMode(EventKey.Mode.NOTIFY);
        for (Registration<?> reg : registrations(key)) {
            if (!passesFilter(reg, origin)) {
                continue;
            }
            virtualThreads.submit(() -> invokeLoggingFailures(reg, carrier, payload));
        }
    }

    /**
     * 顺序通知：listener 按订阅序在调用方线程执行；任一异常停止派发，
     * 包装为 CompletionException 传播。
     *
     * @param <T> 负载类型
     * @param key NOTIFY key
     * @param origin 派发方 scope
     * @param carrier 派发方对象
     * @param payload 事件负载
     */
    public <T> void notifyOrdered(EventKey<T> key, Scope origin, Object carrier, T payload) {
        key.requireMode(EventKey.Mode.NOTIFY);
        for (Registration<?> reg : registrations(key)) {
            if (!passesFilter(reg, origin)) {
                continue;
            }
            invokePropagating(reg, carrier, payload);
        }
    }

    /**
     * 并发通知并返回全部完成的 future（join barrier：持久化 flush 等待全体落账用）。
     * 单 listener 失败记日志，不使 future 异常完成。
     *
     * @param <T> 负载类型
     * @param key NOTIFY key
     * @param origin 派发方 scope
     * @param carrier 派发方对象
     * @param payload 事件负载
     * @return 全部 listener 完成时完成的 future
     */
    public <T> CompletableFuture<Void> notifyAndWait(EventKey<T> key, Scope origin, Object carrier, T payload) {
        key.requireMode(EventKey.Mode.NOTIFY);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Registration<?> reg : registrations(key)) {
            if (!passesFilter(reg, origin)) {
                continue;
            }
            futures.add(CompletableFuture.runAsync(() -> invokeLoggingFailures(reg, carrier, payload), virtualThreads));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    // ────────── WATERFALL ──────────

    /**
     * 中间件链：按订阅序同步执行在调用方（虚拟）线程。listener 不调 next 即短路；
     * next 二次调用抛 IllegalStateException；listener 的受检异常包装为 CompletionException，RuntimeException（语义异常）原样上抛。
     *
     * @param <T> 链的返回类型
     * @param key WATERFALL key
     * @param origin 派发方 scope
     * @param carrier 派发方对象
     * @param args 位置参数（链内不可变，改写经 next(新值...)）
     * @param inner 尾链兜底（所有 listener 都委托时的返回值来源）
     * @return 链的最终返回值
     */
    @SuppressWarnings("unchecked") // 订阅处类型由 subscribeWaterfall 形参绑定到同一 key
    public <T> T waterfall(EventKey<T> key, Scope origin, Object carrier, List<Object> args, Next<T> inner) {
        key.requireMode(EventKey.Mode.WATERFALL);
        List<WaterfallListener<T>> chain = new ArrayList<>();
        for (Registration<?> reg : registrations(key)) {
            if (!passesFilter(reg, origin)) {
                continue;
            }
            chain.add((WaterfallListener<T>) reg.listener());
        }
        try {
            return buildChain(chain, 0, carrier, List.copyOf(args), inner).invoke();
        } catch (CompletionException e) {
            throw e;
        } catch (RuntimeException e) {
            // 语义异常（AbortedException 取消、IAE 校验拒绝等）原样上抛——
            // 与 ScopeImpl.effect 同一先例：裹皮会吃掉调用方的 catch 语义
            throw e;
        } catch (Exception e) {
            throw new CompletionException("waterfall failed for " + key.name(), e);
        }
    }

    /**
     * 查询形态：首个非 null 返回值短路整链；全部返回 null（含 inner 兜底）时为 empty。
     *
     * @param <T> 查询返回类型
     * @param key WATERFALL key
     * @param origin 派发方 scope
     * @param carrier 派发方对象
     * @param args 位置参数
     * @return 首个非 null 结果
     */
    public <T> Optional<T> firstOf(EventKey<T> key, Scope origin, Object carrier, List<Object> args) {
        // 单参 lambda：javac 25 拒绝零参隐式 lambda 目标 varargs 抽象方法
        return Optional.ofNullable(waterfall(key, origin, carrier, args, overrideArgs -> null));
    }

    // ────────── 内部 ──────────

    private List<Registration<?>> registrations(EventKey<?> key) {
        CopyOnWriteArrayList<Registration<?>> list = hooks.get(key);
        return list == null ? List.of() : list;
    }

    @SuppressWarnings("unchecked") // 订阅处类型由 subscribeNotify 形参绑定到同一 key
    private void invokeLoggingFailures(Registration<?> reg, Object carrier, Object payload) {
        try {
            ((EventListener<Object>) reg.listener()).handle(carrier, payload);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "notify listener failed", e);
        }
    }

    @SuppressWarnings("unchecked") // 同上
    private void invokePropagating(Registration<?> reg, Object carrier, Object payload) {
        try {
            ((EventListener<Object>) reg.listener()).handle(carrier, payload);
        } catch (Exception e) {
            throw new CompletionException("notifyOrdered listener failed", e);
        }
    }

    /**
     * listener 在 scope S 订阅，收到从 S 或 S 的任一后代 scope 派发的事件
     * （向上冒泡）；全局订阅（scope 为 null）收到一切派发。
     */
    private static boolean passesFilter(Registration<?> reg, Scope origin) {
        if (reg.scope() == null) {
            return true;
        }
        for (Scope c = origin; c != null; c = c.parent()) {
            if (c == reg.scope()) {
                return true;
            }
        }
        return false;
    }

    private <T> Next<T> buildChain(List<WaterfallListener<T>> chain, int index,
                                   Object carrier, List<Object> args, Next<T> inner) {
        if (index == chain.size()) {
            return inner;
        }
        WaterfallListener<T> listener = chain.get(index);
        // 守卫包住传给 listener 的 rest：每个 listener 的 next 只允许调用一次
        Next<T> rest = invokeOnce(buildChain(chain, index + 1, carrier, args, inner));
        return overrideArgs -> {
            List<Object> effective = overrideArgs.length == 0 ? args : List.of(overrideArgs);
            return listener.handle(carrier, new WaterfallArgs<>(effective, rest));
        };
    }

    private static <T> Next<T> invokeOnce(Next<T> next) {
        AtomicBoolean invokedOnce = new AtomicBoolean(false);
        return overrideArgs -> {
            if (!invokedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("waterfall next() invoked twice");
            }
            return next.invoke(overrideArgs);
        };
    }
}
