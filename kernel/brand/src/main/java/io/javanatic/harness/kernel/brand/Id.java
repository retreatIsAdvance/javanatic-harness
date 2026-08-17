package io.javanatic.harness.kernel.brand;

/**
 * 类型品牌的 ID。结构上是 String，类型上是 T 的载体。
 * 泛型 T 仅供编译器在构造与传参处做类型检查，erasure 后运行时不参与 equals。
 *
 * 对应 dsh Branded&lt;B&gt;（docs/design/08-type-discipline.md §3）。
 *
 * @param <T> 品牌标记（phantom type，各 ID 类型自定义，如 SessionId.Brand）
 * @param value 底层字符串值；非空
 */
public record Id<T>(String value) {

    public Id {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Id value must be non-empty");
        }
    }

    /** 品牌标记接口（phantom，无方法）。每个 ID 类型定义自己的 Brand。 */
    public interface Brand {}

    @Override
    public String toString() {
        return value;
    }
}
