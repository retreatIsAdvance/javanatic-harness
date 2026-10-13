package io.javanatic.harness.skill.local;

import io.javanatic.harness.skill.SkillCandidate;
import io.javanatic.harness.skill.SkillDefinition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 四根发现：优先级、近者胜、目录束格式、frontmatter 校验、损坏跳过、按名装载。 */
class LocalSkillsTest {

    @TempDir
    Path home;

    @TempDir
    Path cwd;

    private LocalSkills skills() {
        return LocalSkills.of(cwd, home);
    }

    private static void write(Path root, String name, String front, String body) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name).resolve("SKILL.md"),
            "---\nname: " + name + "\ndescription: " + front + "\n---\n" + body);
    }

    private Path projectHarness() {
        return cwd.resolve(".harness").resolve("skills");
    }

    @Test
    void fourRootsProjectOverUserOwnFamilyFirst() throws IOException {
        write(projectHarness(), "deploy", "自有", "自有正文");
        write(cwd.resolve(".agents").resolve("skills"), "deploy", "项目跨工具", "跨工具正文");
        write(home.resolve(".harness").resolve("skills"), "deploy", "用户自有", "用户自有正文");
        write(home.resolve(".agents").resolve("skills"), "deploy", "用户跨工具", "用户跨工具正文");

        // provider 契约：全量候选跨根同名原样上交——归并是注册表（停 1）的职责
        List<SkillCandidate> catalog = skills().catalog();
        assertThat(catalog).extracting(SkillCandidate::priority).containsExactly(100, 200, 400, 500);
        // 近者胜发生在装载裁决：rank 最小的胜出根供全文，来源可解释
        assertThat(skills().load("deploy")).hasValueSatisfying(d -> {
            assertThat(d.summary().source()).isEqualTo("project-harness");
            assertThat(d.body()).isEqualTo("自有正文");
        });
    }

    @Test
    void distinctSkillsFromAllRootsAreListedSortedByRegistryNotHere() throws IOException {
        write(projectHarness(), "deploy", "部署", "b");
        write(home.resolve(".agents").resolve("skills"), "review", "评审", "b2");
        List<SkillCandidate> catalog = skills().catalog();
        assertThat(catalog).extracting(c -> c.summary().name()).containsExactly("deploy", "review");
        assertThat(catalog).extracting(SkillCandidate::priority).containsExactly(100, 500);
    }

    @Test
    void missingRootsAndNonSkillDirsAreSilent() throws IOException {
        Files.createDirectories(projectHarness().resolve("not-a-skill")); // 无 SKILL.md
        Files.writeString(projectHarness().resolve("loose.md"), "x");     // 散文件跳过
        assertThat(skills().catalog()).isEmpty();
    }

    @Test
    void malformedEntriesAreSkippedNotFatal() throws IOException {
        write(projectHarness(), "good", "好", "正文");
        // 非法名
        write(projectHarness(), "Bad_Name", "x", "y");
        // 缺 description
        Files.createDirectories(projectHarness().resolve("nodesc"));
        Files.writeString(projectHarness().resolve("nodesc").resolve("SKILL.md"),
            "---\nname: nodesc\n---\nbody");
        // frontmatter 名与目录名不符
        write(projectHarness(), "mismatch", "x", "y");
        Files.writeString(projectHarness().resolve("mismatch").resolve("SKILL.md"),
            "---\nname: other\ndescription: x\n---\ny");
        // 无 frontmatter
        Files.createDirectories(projectHarness().resolve("raw"));
        Files.writeString(projectHarness().resolve("raw").resolve("SKILL.md"), "just text");
        // 空正文
        write(projectHarness(), "empty-body", "x", "\n");

        List<SkillCandidate> catalog = skills().catalog();
        assertThat(catalog).extracting(c -> c.summary().name()).containsExactly("good");
        // 装载坏名/非法名 → 空（已退役/不存在语义，不抛）
        assertThat(skills().load("Bad_Name")).isEmpty();
        assertThat(skills().load("../deploy")).isEmpty();
    }

    @Test
    void loadStripsFrontmatterAndKeepsBodyVerbatim() throws IOException {
        write(projectHarness(), "deploy", "部署指南", "## 步骤\n\n1. 构建\n2. 发布");
        SkillDefinition definition = skills().load("deploy").orElseThrow();
        assertThat(definition.summary().description()).isEqualTo("部署指南");
        assertThat(definition.summary().source()).isEqualTo("project-harness");
        assertThat(definition.body()).isEqualTo("## 步骤\n\n1. 构建\n2. 发布");
    }

    @Test
    void parseRejectsMalformedFrontmatterShapes() {
        assertThat(LocalSkills.parse("no frontmatter")).isNull();
        assertThat(LocalSkills.parse("---\nname: x\n")).isNull();               // 无闭合
        assertThat(LocalSkills.parse("---\nname x\n---\nbody")).isNull();       // 非 key: value
        assertThat(LocalSkills.parse("---\nname: x\n---\n")).isNull();          // 空正文
        assertThat(LocalSkills.parse("---\ndescription: d\n---\nbody")).isNull(); // 缺 name
        assertThat(LocalSkills.parse("---\nextra: k\nname: x\ndescription: d\n---\nbody"))
            .isNotNull(); // 未知键忽略
    }
}
