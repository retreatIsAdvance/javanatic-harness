package io.javanatic.harness.skill.local;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.skill.SkillService;

import java.nio.file.Path;
import java.util.Map;

/**
 * 本地技能供应插件（id "skill-local"，requires "skill"）：把 {@link LocalSkills}
 * 注册进共享 {@link SkillService}。行配置 {@code cwd} = 项目根（工作区单源，与
 * fs-local.root / loop cwd 同值）；用户根缺省取 {@code user.home}，可 {@code home}
 * 覆盖。缺 cwd fail loud（对齐 fs-local.root——根不猜）。
 */
public final class SkillLocalPlugin implements Plugin {

    private final LocalSkills explicit;

    /** 数据组合路径：cwd 从行配置解析（缺失 fail loud）。 */
    public SkillLocalPlugin() {
        this.explicit = null;
    }

    /** @param cwd 项目根（程序化组合的显式选择；用户根取 {@code user.home}） */
    public SkillLocalPlugin(Path cwd) {
        this.explicit = LocalSkills.of(cwd, Path.of(System.getProperty("user.home")));
    }

    /** @param home 用户根显式覆盖（测试与重定向场景） */
    public SkillLocalPlugin(Path cwd, Path home) {
        this.explicit = LocalSkills.of(cwd, home);
    }

    @Override
    public String id() {
        return "skill-local";
    }

    @Override
    public java.util.Set<String> requires() {
        return java.util.Set.of("skill");
    }

    @Override
    public void apply(Scope scope) {
        LocalSkills skills = explicit;
        if (skills == null) {
            Map<String, Object> config = scope.require(ConfigService.KEY).configFor(id());
            String home = ConfigValues.stringValue(config, id(), "home",
                System.getProperty("user.home"));
            skills = LocalSkills.of(
                Path.of(ConfigValues.requireString(config, id(), "cwd")),
                Path.of(home));
        }
        scope.require(SkillService.KEY).register(skills);
    }
}
