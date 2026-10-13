package io.javanatic.harness.skill.local;

import io.javanatic.harness.skill.SkillCandidate;
import io.javanatic.harness.skill.SkillDefinition;
import io.javanatic.harness.skill.SkillProvider;
import io.javanatic.harness.skill.SkillSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * 四根本地发现（it26 T4 裁定 B）：项目/用户 × `.harness`/`.agents`，rank
 * 100/200/400/500——项目覆盖用户、同层自有优先（dsh 同构；dsh 第 3 根
 * custom 不采）。格式 = {@code <root>/<name>/SKILL.md} 目录束，一层深；
 * frontmatter 必填 {@code name}+{@code description}（目录名即技能名，
 * kebab-case 校验）；条目非法/损坏跳过并 WARN、不抛（不杀轮——dsh 同款容错）。
 * 每轮重查、无缓存（SkillProvider 契约）；只读，不跟随符号链接（与 fs_search
 * 同姿态）。
 */
public final class LocalSkills implements SkillProvider {

    private static final System.Logger LOG = System.getLogger(LocalSkills.class.getName());

    private static final String SKILL_FILE = "SKILL.md";

    /** 名称形态：kebab-case（目录名即技能名）。 */
    private static final String NAME_PATTERN = "[a-z0-9]+(-[a-z0-9]+)*";

    /** 四根：路径 + rank（小者胜）+ 来源标签（模型可见、可解释）。 */
    private record Root(Path path, int priority, String source) {}

    private final List<Root> roots;

    private LocalSkills(List<Root> roots) {
        this.roots = List.copyOf(roots);
    }

    /** 标准四根：项目根（cwd）与用户根（home）各自派生 `.harness`/`.agents` 两族。 */
    public static LocalSkills of(Path cwd, Path home) {
        List<Root> roots = new ArrayList<>();
        roots.add(new Root(cwd.resolve(".harness").resolve("skills"), 100, "project-harness"));
        roots.add(new Root(cwd.resolve(".agents").resolve("skills"), 200, "project-agents"));
        roots.add(new Root(home.resolve(".harness").resolve("skills"), 400, "user-harness"));
        roots.add(new Root(home.resolve(".agents").resolve("skills"), 500, "user-agents"));
        return new LocalSkills(roots);
    }

    @Override
    public String id() {
        return "skill-local";
    }

    @Override
    public List<SkillCandidate> catalog() {
        List<SkillCandidate> candidates = new ArrayList<>();
        for (Root root : roots) {
            scanRoot(root, candidates);
        }
        return List.copyOf(candidates);
    }

    @Override
    public Optional<SkillDefinition> load(String name) {
        if (!name.matches(NAME_PATTERN)) {
            return Optional.empty(); // 非法名与 catalog 同闸：目录 traversal 面只认 kebab-case
        }
        // 胜出根 = rank 最小的、当前仍含该名的根——与 catalog 同一裁决序
        for (Root root : roots) {
            Path dir = root.path().resolve(name);
            if (!Files.isRegularFile(dir.resolve(SKILL_FILE))) {
                continue;
            }
            return read(dir, name).map(front -> new SkillDefinition(
                new SkillSummary(name, front.description(), root.source()), front.body()));
        }
        return Optional.empty();
    }

    /** 单根扫描：一层深目录束，含 SKILL.md 的目录为候选；非法/损坏跳过并 WARN。 */
    private void scanRoot(Root root, List<SkillCandidate> out) {
        if (!Files.isDirectory(root.path())) {
            return; // 根不存在是常态（多数项目/用户没有技能目录）
        }
        List<Path> dirs;
        try (var entries = Files.list(root.path())) {
            dirs = entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "skill root unreadable, skipping: " + root.path(), e);
            return;
        }
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            if (!name.matches(NAME_PATTERN)) {
                warnSkip(name, root, "name not kebab-case");
                continue;
            }
            Optional<Frontmatter> front = read(dir, name);
            if (front.isEmpty()) {
                continue; // read 内部已 WARN 具体原因
            }
            out.add(new SkillCandidate(
                new SkillSummary(name, front.get().description(), root.source()), root.priority()));
        }
    }

    /** 读单束：SKILL.md 缺失/不可读/frontmatter 不合规 → 空 + WARN（调用方跳过）。 */
    private Optional<Frontmatter> read(Path dir, String name) {
        Path file = dir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(file)) {
            warnSkip(name, dir, "missing " + SKILL_FILE);
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "skill file unreadable, skipping: " + file, e);
            return Optional.empty();
        }
        Parsed parsed = parse(text);
        if (parsed == null) {
            warnSkip(name, dir, "frontmatter must start/end with '---' and declare name+description");
            return Optional.empty();
        }
        if (!parsed.name().equals(name)) {
            warnSkip(name, dir, "frontmatter name '" + parsed.name() + "' != directory name");
            return Optional.empty();
        }
        return Optional.of(new Frontmatter(parsed.description(), parsed.body()));
    }

    /**
     * 最小 frontmatter 解析（T5：自持、零新增依赖）——首行 {@code ---}、逐行
     * 顶层 {@code key: value} 标量、遇闭 {@code ---} 止；其后为正文。不合形返回 null。
     */
    static Parsed parse(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().strip().equals("---")) {
            return null;
        }
        String name = null;
        String description = null;
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().equals("---")) {
                close = i;
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return null; // frontmatter 区只允许 key: value 行
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                return null;
            }
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                default -> { /* 未知键忽略：前向兼容 */ }
            }
        }
        if (close < 0 || name == null || description == null) {
            return null;
        }
        String body = String.join("\n", lines.subList(close + 1, lines.size())).strip();
        if (body.isEmpty()) {
            return null; // 空正文不是技能
        }
        return new Parsed(name, description, body);
    }

    private static void warnSkip(String name, Object at, String reason) {
        LOG.log(System.Logger.Level.WARNING, "skill ''{0}'' at {1} skipped: {2}", name, at, reason);
    }

    record Parsed(String name, String description, String body) {}

    private record Frontmatter(String description, String body) {}
}
