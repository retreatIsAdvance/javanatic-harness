/**
 * harness-skill-skill — skill capability Definition（design: docs/design/05-capability-seam.md §4）。
 * Providers 实现；consumers 只见此契约。SkillPlugin 把注册表提供进共享 scope。
 */
module io.javanatic.harness.skill.skill {
    requires io.javanatic.harness.kernel;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.skill.SkillPlugin;

    exports io.javanatic.harness.skill;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.skill;
}
