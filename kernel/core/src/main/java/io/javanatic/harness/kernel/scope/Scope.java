package io.javanatic.harness.kernel.scope;

import io.javanatic.harness.kernel.events.ScopedEvents;

import java.util.Optional;

/**
 * 唯一的生命周期 + 可见性容器。
 *
 * 生命周期：effect 栈按 LIFO 回收（后注册先回收），close 级联子 scope。
 * 可见性：服务沿父链向上解析；本 scope 注册的同名服务覆盖（shadow）父级。
 * 事件：{@link #events()} 返回绑定本 scope 的订阅视图，订阅随 scope 回收。
 *
 * 插件在自己的 scope（通常是 Runtime 为它建的子 scope）上注册一切；
 * 不持有全局静态状态是插件的义务（R3 边界，docs/design/01-kernel.md §8）。
 */
public interface Scope extends AutoCloseable {

    /**
     * 注册服务到本 scope。本 scope 内重复注册同 key 抛 IllegalStateException；
     * 子 scope 注册同 key 覆盖父级（overlay/shadow，preset 组合用它）。
     * 注册即 effect：返回的 Disposable close 即注销；
     * 调用方不 close 时，scope close 按 LIFO 兜底回收。
     *
     * @param <T> 服务接口类型
     * @param key 服务标识
     * @param impl 服务实现
     * @return 可注销句柄
     */
    <T> Disposable provide(ServiceKey<T> key, T impl);

    /**
     * 沿父链向上查找服务；本 scope 起查。每次访问重查、不缓存——
     * provider scope close 即注销，任何后续 resolve 都查不到（R3 结构性保证）。
     *
     * @param <T> 服务接口类型
     * @param key 服务标识
     * @return 命中的实现；链上无提供者时为 empty
     */
    <T> Optional<T> resolve(ServiceKey<T> key);

    /**
     * resolve 的 fail-loud 版：沿链无提供者时抛 ServiceNotAvailableException。
     *
     * @param <T> 服务接口类型
     * @param key 服务标识
     * @return 命中的实现
     */
    <T> T require(ServiceKey<T> key);

    /**
     * 注册一个 effect：register() 执行注册副作用并返回回收器，回收器入栈。
     * register 抛异常则注册失败、无回收器入栈（原子性）。
     *
     * @param effect 注册副作用
     * @return 可回收句柄
     */
    Disposable effect(Effect effect);

    /**
     * 纯 teardown 挂载（无注册副作用）。等价于 {@code effect(() -> closeable)}。
     *
     * @param closeable 回收器
     * @return 可回收句柄
     */
    Disposable onClose(AutoCloseable closeable);

    /**
     * 派生子 scope：新的生命周期域 + 服务 overlay 层。父 close 级联子。
     *
     * @return 子 scope
     */
    Scope child();

    /**
     * 父 scope；root 返回 null。事件冒泡与 overlay 以父链为据。
     *
     * @return 父 scope；本 scope 为 root 时为 null
     */
    Scope parent();

    /**
     * 绑定本 scope 的事件订阅视图（订阅随 scope 回收）。
     *
     * @return 订阅视图
     */
    ScopedEvents events();

    /** LIFO 回收本 scope 全部 effect，并级联所有子 scope。幂等。 */
    @Override
    void close();

    /** 是否已关闭。 */
    boolean isClosed();
}
