/**
 * harness-kernel-brand — type-branded Id: a String carrier parameterized by a
 * phantom Brand type, checked at construction and use sites, erased at runtime
 * (08 §3).
 */
module io.javanatic.harness.kernel.brand {
    exports io.javanatic.harness.kernel.brand;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.kernel.brand;
}
