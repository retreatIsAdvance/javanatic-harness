package io.javanatic.harness.kernel.scope;

/**
 * {@link Scope#require(ServiceKey)} 沿父链无提供者时抛出。
 * 消息携带 key name 与解析起点 scope，指向组合缺口。
 */
public final class ServiceNotAvailableException extends RuntimeException {

    /**
     * @param key 查找失败的服务标识
     * @param origin 解析起点 scope
     */
    public ServiceNotAvailableException(ServiceKey<?> key, Scope origin) {
        super("Service '" + key + "' not provided in scope chain from " + origin);
    }
}
