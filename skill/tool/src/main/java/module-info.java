/**
 * harness-skill-tool — skill capability Consumer（design: docs/design/05-capability-seam.md §4）：
 * skill(name) 装载工具 + 轮首目录装载（agent/pre-step 挂点）+ skill/catalog
 * 扩展事件（ServiceLoader codec）+ Dynamic 目录提示段（it26 停 2）。
 * 首个非 examples 依赖 agent-loop 的模块（02 依赖列已登记）。
 */
module io.javanatic.harness.skill.tool {
    requires io.javanatic.harness.kernel;
    requires io.javanatic.harness.kernel.config;
    requires io.javanatic.harness.skill.skill;
    requires io.javanatic.harness.core.tools;
    requires io.javanatic.harness.core.system.prompt;
    requires io.javanatic.harness.core.agent;
    requires io.javanatic.harness.core.agent.loop;
    requires io.javanatic.harness.core.session;
    requires io.javanatic.harness.session.persistence;

    provides io.javanatic.harness.kernel.plugin.Plugin with io.javanatic.harness.skill.tool.SkillToolPlugin;
    provides io.javanatic.harness.session.persistence.SessionEventCodec with io.javanatic.harness.skill.tool.SkillCatalogCodec;

    exports io.javanatic.harness.skill.tool;
    // JUnit 运行时反射实例化同包测试类需要 opens；只放开运行时反射，不改编译期可见性
    opens io.javanatic.harness.skill.tool;
}
