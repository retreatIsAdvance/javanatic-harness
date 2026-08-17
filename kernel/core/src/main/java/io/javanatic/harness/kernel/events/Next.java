package io.javanatic.harness.kernel.events;

/**
 * waterfall 的 next：委托给链上的下一个 listener。
 * 零参调用直传当前 args；带参调用以新 args 下传（改写即换值，args 本身不可变）。
 * 每个 next 实例只允许调用一次。
 *
 * @param <T> 链的返回类型
 */
@FunctionalInterface
public interface Next<T> {

    /**
     * @param overrideArgs 空 = 直传当前 args；非空 = 改写后的位置参数
     * @return 尾链的执行结果
     */
    T invoke(Object... overrideArgs) throws Exception;
}
