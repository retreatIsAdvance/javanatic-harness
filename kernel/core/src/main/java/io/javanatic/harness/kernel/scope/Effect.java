package io.javanatic.harness.kernel.scope;

/**
 * 注册副作用与回收器的分离：{@link #register()} 的返回值才是 effect 栈持有并回收的东西。
 * register 抛异常则注册失败、无回收器入栈（原子性）。
 */
@FunctionalInterface
public interface Effect {

    /**
     * 执行注册副作用并返回回收器；异常上抛且不留任何已注册状态。
     *
     * @return 回收器，由 effect 栈持有，close 时回收本次注册
     */
    AutoCloseable register() throws Exception;
}
