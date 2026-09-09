package io.javanatic.harness.preset;

import io.javanatic.harness.kernel.config.ConfigRowSpec;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Scope;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * preset 默认实现:扫描 root 下的 {@code <id>/preset.yml};mount 经
 * {@code agentScope.mountView()} 逐插件装载(原子回滚,01 §7),行引用的
 * 插件缺失时列全清单 fail loud。
 */
final class PresetService implements AgentPresets {

    private final Path root;

    PresetService(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public List<AgentPreset> list() {
        Map<String, AgentPreset> byName = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve("preset.yml");
                if (Files.isRegularFile(file)) {
                    AgentPreset preset = parse(dir.getFileName().toString(), file);
                    byName.put(preset.name(), preset);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("preset scan failed in " + root, e);
        }
        return List.copyOf(byName.values());
    }

    @Override
    public AgentPreset resolve(String id) {
        Objects.requireNonNull(id, "id");
        Path file = root.resolve(id).resolve("preset.yml");
        if (!Files.isRegularFile(file)) {
            throw new java.util.NoSuchElementException("preset not found: " + id);
        }
        return parse(id, file);
    }

    @Override
    public AgentPreset mount(Scope agentScope, String id) {
        AgentPreset preset = resolve(id);
        PluginLoader loader = new PluginLoader();
        Map<String, Plugin> discovered = loader.discover();
        List<String> missing = new ArrayList<>();
        List<Plugin> ordered = new ArrayList<>();
        for (ConfigRowSpec.Include row : preset.rows()) {
            Plugin plugin = discovered.get(row.plugin());
            if (plugin == null) {
                missing.add(row.plugin());
            } else {
                ordered.add(plugin);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("preset '" + id + "' references unknown plugins: "
                + String.join(", ", missing));
        }
        loader.loadAllUnder(agentScope, ordered);
        return preset;
    }

    /** preset.yml → AgentPreset;非 Include 行(动作语义)fail loud。 */
    @SuppressWarnings("unchecked")
    private AgentPreset parse(String id, Path file) {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("preset parse failed: " + file, e);
        }
        if (root == null) {
            throw new IllegalStateException("preset empty: " + file);
        }
        String description = root.get("description") == null ? "" : root.get("description").toString();
        List<ConfigRowSpec.Include> rows = new ArrayList<>();
        Object raw = root.get("rows");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    throw new IllegalStateException("preset row must be a mapping in " + file);
                }
                Map<String, Object> map = (Map<String, Object>) row;
                rejectActions(id, map);
                String plugin = stringOf(map, "plugin");
                Map<String, Object> config = map.get("config") instanceof Map<?, ?> cfg
                    ? toStringKeys(cfg) : Map.of();
                rows.add(new ConfigRowSpec.Include(plugin, config, null));
            }
        }
        return new AgentPreset(id, description, rows);
    }

    /** preset 只承载 Include;patch 动作是组合层的事(07)。 */
    private static void rejectActions(String id, Map<String, Object> row) {
        for (String action : List.of("replace", "remove", "after", "before")) {
            if (row.containsKey(action)) {
                throw new IllegalStateException("preset '" + id + "' row carries patch action '"
                    + action + "' — presets only include");
            }
        }
    }

    private static String stringOf(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalStateException("preset row missing '" + key + "'");
        }
        return text;
    }

    private static Map<String, Object> toStringKeys(Map<?, ?> map) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> out.put(key.toString(), value));
        return out;
    }
}
