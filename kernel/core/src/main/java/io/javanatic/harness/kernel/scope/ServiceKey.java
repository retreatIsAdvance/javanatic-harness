package io.javanatic.harness.kernel.scope;

/**
 * 类型品牌的服务标识。结构上只有 name，类型上是 T 的载体。
 * 泛型 T 仅供编译器在 provide / resolve 处做类型检查，运行时不参与 equals（erasure）。
 *
 * key 是共享常量：由 seam 的 Definition 模块持有唯一 public static final 实例，
 * Provider 与 Consumer 都 import 该常量，不各自 new。name 拼写错误的出错面
 * 因此收敛到 Definition 一处（docs/design/01-kernel.md §2）。
 *
 * @param <T> key 标识的服务接口类型
 * @param name 服务名；全局唯一，kebab-case
 */
public record ServiceKey<T>(String name) {

    public ServiceKey {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("ServiceKey name must be non-empty");
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
