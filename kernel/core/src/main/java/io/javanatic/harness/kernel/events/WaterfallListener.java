package io.javanatic.harness.kernel.events;

/**
 * WATERFALL 模式监听器：包一层 {@link WaterfallArgs}（args + next），返回值沿链上传。
 * 不调 {@code args.next()} 即短路；next 二次调用抛 IllegalStateException。
 *
 * @param <T> 链的返回类型
 */
@FunctionalInterface
public interface WaterfallListener<T> {

    /**
     * @param carrier 派发方对象
     * @param args 参数包（args 不可变 + next 委托）
     * @return 本 listener 的结果，成为链在该位置的返回值
     */
    T handle(Object carrier, WaterfallArgs<T> args) throws Exception;
}
