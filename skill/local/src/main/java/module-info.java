/**
 * harness-skill-local — local skill provider (plugin id "skill-local", it26 停 3).
 * 四根发现：项目/用户 × `.harness`/`.agents`，近者胜；dsh 对照 `skill/skill-filesystem`。
 */
module io.javanatic.harness.skill.local {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.skill.skill;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.skill.local.SkillLocalPlugin;

    exports io.javanatic.harness.skill.local;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.skill.local;
}
