package io.javanatic.harness.kernel.scope;

import io.javanatic.harness.kernel.events.ScopedEvents;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scope 的唯一实现。包私有：外部只见 {@link Scope} 接口与构造 root 的 {@link Runtime}。
 *
 * 服务：每 scope 一张表；解析沿父链逐层查找，每次访问重查、不缓存——
 * provider scope close 即注销，任何后续 resolve 都查不到（R3 的结构性保证）。
 * 回收：effect 栈 LIFO；close 用 CAS 保证只有一个线程排水，其余并发 close 直接返回。
 */
final class ScopeImpl implements Scope {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private enum Status { ACTIVE, CLOSED }

    private final ScopeImpl parent;
    private final Runtime runtime;
    private final int id = SEQ.incrementAndGet();
    private final ConcurrentHashMap<ServiceKey<?>, Object> services = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<AutoCloseable> effectStack = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private volatile ScopedEvents eventsView;

    ScopeImpl(ScopeImpl parent, Runtime runtime) {
        this.parent = parent;
        this.runtime = runtime;
    }

    @Override
    public <T> Disposable provide(ServiceKey<T> key, T impl) {
        ensureActive();
        return effect(() -> registerService(key, impl));
    }

    /**
     * 无栈注册通道（内核装配用）：写本 scope 服务表，返回"仅注销"的回收器，
     * 不在本 scope 的 effect 栈登记。ScopeImpl.provide 不用它（走本栈）；
     * PluginScope 用它把注销器登记到插件私有栈，保证插件 teardown 时
     * 自己的服务仍可见、摘除恒为最后一步。
     */
    <T> AutoCloseable registerService(ServiceKey<T> key, T impl) {
        ensureActive();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(impl, "impl");
        Object prev = services.putIfAbsent(key, impl);
        if (prev != null) {
            throw new IllegalStateException("Service " + key + " already registered in this scope");
        }
        return () -> services.remove(key, impl);
    }

    @Override
    @SuppressWarnings("unchecked") // 入表处 provide 已保证 impl 与 key 的 T 一致
    public <T> Optional<T> resolve(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        ScopeImpl cursor = this;
        while (cursor != null) {
            Object impl = cursor.services.get(key);
            if (impl != null) {
                return Optional.of((T) impl);
            }
            cursor = cursor.parent;
        }
        return Optional.empty();
    }

    @Override
    public <T> T require(ServiceKey<T> key) {
        return resolve(key).orElseThrow(() -> new ServiceNotAvailableException(key, this));
    }

    @Override
    public Disposable effect(Effect effect) {
        ensureActive();
        Objects.requireNonNull(effect, "effect");
        final AutoCloseable disposer;
        try {
            disposer = effect.register();
        } catch (RuntimeException e) {
            // fail-loud 语义异常（如 duplicate provide 的 IllegalStateException）原样上抛，
            // 不裹第二层皮丢失消息；仅受检异常需要包装以穿过无 throws 签名
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Effect register failed; nothing registered", e);
        }
        Objects.requireNonNull(disposer, "disposer");
        effectStack.addLast(disposer);
        return new Disposable(disposer, () -> effectStack.remove(disposer));
    }

    @Override
    public Disposable onClose(AutoCloseable closeable) {
        Objects.requireNonNull(closeable, "closeable");
        return effect(() -> closeable);
    }

    @Override
    public Scope child() {
        ensureActive();
        ScopeImpl child = new ScopeImpl(this, runtime);
        // 子 scope 的级联回收是父栈上的一个 entry：父 close 时 LIFO 到它即关子
        effectStack.addLast(child::close);
        return child;
    }

    @Override
    public Scope parent() {
        return parent;
    }

    @Override
    public ScopedEvents events() {
        ScopedEvents view = eventsView;
        if (view == null) {
            synchronized (this) {
                if (eventsView == null) {
                    eventsView = runtime.events().forScope(this);
                }
                view = eventsView;
            }
        }
        return view;
    }

    /** 本 scope 所属 Runtime（同包装配用：PluginScope 借它取事件总线）。 */
    Runtime runtime() {
        return runtime;
    }

    @Override
    public void close() {
        if (!status.compareAndSet(Status.ACTIVE, Status.CLOSED)) {
            return;
        }
        AutoCloseable c;
        while ((c = effectStack.pollLast()) != null) {
            try {
                c.close();
            } catch (Exception e) {
                runtime.logDisposeError(this, e);
            }
        }
    }

    @Override
    public boolean isClosed() {
        return status.get() == Status.CLOSED;
    }

    private void ensureActive() {
        if (status.get() != Status.ACTIVE) {
            throw new IllegalStateException(this + " is closed");
        }
    }

    @Override
    public String toString() {
        return "Scope#" + id;
    }
}
