package io.javanatic.harness.skill.local;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.skill.SkillPlugin;
import io.javanatic.harness.skill.SkillService;
import io.javanatic.harness.skill.SkillSummary;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件装配路径（停 3 复核 f1：此前无任何测试实例化本插件）——显式构造器与行配置两态注册进 SkillService。 */
class SkillLocalPluginTest {

    @TempDir
    Path cwd;

    @TempDir
    Path home;

    private static void write(Path root, String name, String description, String body) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name).resolve("SKILL.md"),
            "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
    }

    @Test
    void explicitConstructorRegistersIntoService() throws IOException {
        write(cwd.resolve(".harness").resolve("skills"), "deploy", "部署指南", "自有正文");
        write(home.resolve(".agents").resolve("skills"), "review", "评审清单", "用户正文");
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SkillPlugin(), new SkillLocalPlugin(cwd, home)));
            SkillService skills = rt.root().require(SkillService.KEY);

            assertThat(skills.catalog().skills())
                .extracting(SkillSummary::name, SkillSummary::source)
                .containsExactly(
                    Tuple.tuple("deploy", "project-harness"),
                    Tuple.tuple("review", "user-agents"));
            assertThat(skills.load("deploy"))
                .hasValueSatisfying(d -> assertThat(d.body()).isEqualTo("自有正文"));
        }
    }

    /** 无参构造：cwd 与 home 均从行配置解析（home 缺省 user.home——测试里显式钉住保确定性）。 */
    @Test
    void noArgResolvesCwdAndHomeFromConfig() throws IOException {
        write(cwd.resolve(".harness").resolve("skills"), "deploy", "部署指南", "自有正文");
        write(home.resolve(".agents").resolve("skills"), "review", "评审清单", "用户正文");
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> "skill-local".equals(id)
                ? Map.of("cwd", cwd.toString(), "home", home.toString()) : Map.of());
            new PluginLoader().loadAll(rt, List.of(new SkillPlugin(), new SkillLocalPlugin()));
            assertThat(rt.root().require(SkillService.KEY).catalog().skills())
                .extracting(SkillSummary::name)
                .containsExactly("deploy", "review");
        }
    }

    /** 缺 cwd fail loud（对齐 fs-local.root——根不猜）。 */
    @Test
    void missingCwdFailsLoud() {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> Map.of());
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt,
                List.of(new SkillPlugin(), new SkillLocalPlugin())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("config missing required 'cwd' for plugin 'skill-local'");
        }
    }
}
