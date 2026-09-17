/**
 * harness-kernel-brand — skeleton module; JPMS dependency graph enforced from day one
 * (design: docs/design/02-module-layout.md).
 */
module io.javanatic.harness.kernel.brand {
    exports io.javanatic.harness.kernel.brand;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.kernel.brand;
}
