package io.javanatic.harness.skill;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;

import java.util.Set;

/**
 * 技能服务插件（id "skill"）：把 {@link SkillRegistry} 以 {@link SkillService#KEY}
 * 提供进共享 scope——注册表构造入口在本模块内（构造者须同模块，对齐
 * ToolsPlugin/ScopedRegistry 先例；provider 与 consumer 经 scope 解析，it26 停 2）。
 */
public final class SkillPlugin implements Plugin {

    @Override
    public String id() {
        return "skill";
    }

    @Override
    public Set<String> requires() {
        return Set.of();
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(SkillService.KEY, new SkillRegistry());
    }
}
