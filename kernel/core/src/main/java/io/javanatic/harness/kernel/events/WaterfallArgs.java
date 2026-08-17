package io.javanatic.harness.kernel.events;

import java.util.List;
import java.util.Objects;

/**
 * waterfall 参数包。args 不可变，listener 改写后经 {@code next(新值...)} 下传。
 *
 * @param <T> 链的返回类型
 * @param args 位置参数（不可变）
 * @param rest 委托尾链
 */
public record WaterfallArgs<T>(List<Object> args, Next<T> rest) {

    public WaterfallArgs {
        Objects.requireNonNull(rest, "rest");
        args = List.copyOf(args);
    }

    /**
     * 委托尾链：零参调用直传当前 args；带参调用以新 args 下传。
     * 每个 rest 实例只允许调用一次（二次抛 IllegalStateException）。
     *
     * @param overrideArgs 空 = 直传当前 args；非空 = 改写后的位置参数
     * @return 尾链的执行结果
     */
    public T next(Object... overrideArgs) throws Exception {
        return rest.invoke(overrideArgs);
    }
}
