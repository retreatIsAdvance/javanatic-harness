package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.ConfigRowSpec;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML → 行/bundle 的装载边界(07 §1):flat 行 Map → {@link ConfigRowSpec}
 * 判别联合在此一次性拦截矛盾组合(replace+remove、锚点与替换并存等)。
 * bundle 经 classpath 资源 {@code META-INF/harness/bundle.yml} 发现。
 */
public final class YamlRows {

    /** bundle 资源路径(每 bundle 一份: name/description/rows)。 */
    public static final String BUNDLE_RESOURCE = "META-INF/harness/bundle.yml";

    /** 一个 bundle:name + 行序。 */
    public record Bundle(String name, String description, List<ConfigRowSpec> rows) {
    }

    /** 一个 profile:name + policy + bundle 名序 + patch 行。 */
    public record Profile(String name, Policy policy, List<String> bundles,
                          List<ConfigRowSpec> rows) {
    }

    private YamlRows() {
    }

    /** @return classpath 上全部 bundle(重名 fail loud) */
    public static List<Bundle> discoverBundles() throws IOException {
        Map<String, Bundle> byName = new LinkedHashMap<>();
        for (java.util.Enumeration<java.net.URL> urls =
                YamlRows.class.getClassLoader().getResources(BUNDLE_RESOURCE); urls.hasMoreElements();) {
            java.net.URL url = urls.nextElement();
            try (InputStream in = url.openStream()) {
                Bundle bundle = parseBundle(in);
                Bundle prev = byName.putIfAbsent(bundle.name(), bundle);
                if (prev != null) {
                    throw new IllegalStateException("duplicate bundle name: " + bundle.name());
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /** @param profilePath profile YAML 文件路径 @return 解析的 profile */
    public static Profile parseProfile(Path profilePath) throws IOException {
        try (InputStream in = Files.newInputStream(profilePath)) {
            return parseProfile(in);
        }
    }

    /** @param yaml YAML 流 @return bundle */
    @SuppressWarnings("unchecked")
    public static Bundle parseBundle(InputStream yaml) {
        Map<String, Object> root = asMap(new Yaml().load(yaml), "bundle document");
        String name = requireString(root, "name");
        String description = stringOf(root, "description", "");
        List<ConfigRowSpec> rows = parseRows(root.get("rows"), "bundle '" + name + "'");
        return new Bundle(name, description, rows);
    }

    /** @param yaml YAML 流 @return profile */
    @SuppressWarnings("unchecked")
    public static Profile parseProfile(InputStream yaml) {
        Map<String, Object> root = asMap(new Yaml().load(yaml), "profile document");
        String name = requireString(root, "name");
        Policy policy = root.containsKey("policy")
            ? Policy.valueOf(stringOf(root, "policy", "STANDARD").toUpperCase()) : Policy.STANDARD;
        List<String> bundles = new ArrayList<>();
        Object raw = root.get("bundles");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                bundles.add(item.toString());
            }
        }
        List<ConfigRowSpec> rows = parseRows(root.get("rows"), "profile '" + name + "'");
        return new Profile(name, policy, bundles, rows);
    }

    /** flat 行 Map → 判别联合;矛盾组合在此 fail loud。 */
    @SuppressWarnings("unchecked")
    public static List<ConfigRowSpec> parseRows(Object raw, String source) {
        List<ConfigRowSpec> rows = new ArrayList<>();
        if (raw == null) {
            return rows;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(source + ": 'rows' must be a list");
        }
        int index = 0;
        for (Object item : list) {
            Map<String, Object> map = asMap(item, source + " row #" + index);
            rows.add(parseRow(map, source + " row #" + index));
            index++;
        }
        return rows;
    }

    private static ConfigRowSpec parseRow(Map<String, Object> map, String where) {
        String plugin = requireString(map, "plugin");
        // 矛盾检查作用于 flat 键共存(分解之前)——主语是原始 Map,不传拆散的局部
        rejectContradictions(map, plugin, where);
        Map<String, Object> config = map.get("config") instanceof Map<?, ?> configMap
            ? toStringKeys(configMap) : Map.of();
        if (boolOf(map, "remove")) {
            return new ConfigRowSpec.Remove(plugin);
        }
        String after = stringOf(map, "after", null);
        String before = stringOf(map, "before", null);
        if (after != null || before != null) {
            ConfigRowSpec.Anchor anchor = after != null
                ? ConfigRowSpec.Anchor.after(after) : ConfigRowSpec.Anchor.before(before);
            return new ConfigRowSpec.Insert(plugin, config,
                stringOf(map, "disabled", null), anchor);
        }
        if (boolOf(map, "replace")) {
            return new ConfigRowSpec.Replace(plugin, config, stringOf(map, "disabled", null));
        }
        return new ConfigRowSpec.Include(plugin, config, stringOf(map, "disabled", null));
    }

    private static void rejectContradictions(Map<String, Object> map, String plugin, String where) {
        boolean anchored = map.containsKey("after") || map.containsKey("before");
        if (boolOf(map, "replace") && boolOf(map, "remove")) {
            throw new IllegalStateException(where + ": '" + plugin + "' 同时 replace 与 remove");
        }
        if (anchored && (boolOf(map, "replace") || boolOf(map, "remove"))) {
            throw new IllegalStateException(where + ": '" + plugin + "' 锚点插入不能与 replace/remove 并用");
        }
        if (map.containsKey("after") && map.containsKey("before")) {
            throw new IllegalStateException(where + ": '" + plugin + "' after 与 before 只能其一");
        }
        if (boolOf(map, "remove") && (map.containsKey("config") || map.containsKey("disabled"))) {
            throw new IllegalStateException(where + ": '" + plugin + "' remove 行不能带 config/disabled");
        }
    }

    private static Map<String, Object> toStringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(key.toString(), value));
        return out;
    }

    private static Map<String, Object> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(what + " must be a mapping");
        }
        return toStringKeys(map);
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalStateException("missing required '" + key + "'");
        }
        return text;
    }

    private static String stringOf(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    private static boolean boolOf(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return Boolean.TRUE.equals(value);
    }
}
