package io.javanatic.harness.kernel.events;

/**
 * NOTIFY 模式监听器。handle 允许阻塞：notify / notifyAndWait 的派发在虚拟线程上。
 *
 * @param <T> 事件负载类型
 */
@FunctionalInterface
public interface EventListener<T> {

    /**
     * @param carrier 派发方对象（如 Session/Agent 实例），供需要上下文的监听器使用
     * @param payload 事件负载
     */
    void handle(Object carrier, T payload) throws Exception;
}
