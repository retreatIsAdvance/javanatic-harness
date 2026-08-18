package io.javanatic.harness.kernel.events;

import io.javanatic.harness.kernel.scope.Disposable;

/**
 * {@link io.javanatic.harness.kernel.scope.Scope#events()} 返回的订阅视图。
 *
 * 订阅挂在所属 scope 的 effect 栈上：scope close 时订阅自动注销，
 * 插件不需要（也不应该）手工管理订阅回收。
 */
public interface ScopedEvents {

    /**
     * 本 scope 订阅：收到本 scope 及其后代 scope 派发的事件（向上冒泡）。
     *
     * @param <T> 负载类型
     * @param key NOTIFY key
     * @param listener 监听器
     * @return 可注销句柄（scope close 兜底回收）
     */
    <T> Disposable on(EventKey<T> key, EventListener<? super T> listener);

    /**
     * 全局订阅：忽略 scope 过滤，收到一切派发；仍随本 scope 回收。
     *
     * @param <T> 负载类型
     * @param key NOTIFY key
     * @param listener 监听器
     * @return 可注销句柄
     */
    <T> Disposable onGlobal(EventKey<T> key, EventListener<? super T> listener);

    /**
     * 本 scope 的 waterfall 订阅。
     *
     * @param <T> 链的返回类型
     * @param key WATERFALL key
     * @param listener 中间件
     * @return 可注销句柄
     */
    <T> Disposable onWaterfall(EventKey<T> key, WaterfallListener<? super T> listener);
}
