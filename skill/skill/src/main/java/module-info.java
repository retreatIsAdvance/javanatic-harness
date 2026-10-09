/**
 * harness-skill-skill — skill capability Definition（design: docs/design/05-capability-seam.md §4）。
 * Providers 实现；consumers 只见此契约（it26 停 1）。
 */
module io.javanatic.harness.skill.skill {
    requires io.javanatic.harness.kernel;

    exports io.javanatic.harness.skill;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.skill;
}
