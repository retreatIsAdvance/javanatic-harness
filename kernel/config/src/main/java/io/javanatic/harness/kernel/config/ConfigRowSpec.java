package io.javanatic.harness.kernel.config;

import java.util.Map;
import java.util.Objects;

/**
 * 一行组合——判别联合：互斥动作（追加/替换/移除/锚点插入）用变体表达，
 * 不落布尔字段（07 §2；互斥布尔堆叠是被摊平的判别式 if 链）。bundle 行
 * 通常是 {@link Include}（可带 disabled），patch 行按动作选变体；
 * flat YAML → 变体的解析与组合矛盾拦截（replace+remove 同现等）归装载
 * 工厂（bundle/base），下游合并器对变体穷尽 switch。
 */
public sealed interface ConfigRowSpec {

    /** 追加；同 id 已存在则替换整行（宽松）。 */
    record Include(String plugin, Map<String, Object> config, String disabled)
            implements ConfigRowSpec {

        /** @throws NullPointerException/IllegalArgumentException plugin 非法时 */
        public Include {
            plugin = requirePlugin(plugin);
            config = normalize(config);
        }
    }

    /** 强制替换；目标不存在由合并器 fail loud（严格）。 */
    record Replace(String plugin, Map<String, Object> config, String disabled)
            implements ConfigRowSpec {

        /** @throws NullPointerException/IllegalArgumentException plugin 非法时 */
        public Replace {
            plugin = requirePlugin(plugin);
            config = normalize(config);
        }
    }

    /** 移除；目标不存在由合并器 fail loud。 */
    record Remove(String plugin) implements ConfigRowSpec {

        /** @throws NullPointerException/IllegalArgumentException plugin 非法时 */
        public Remove {
            plugin = requirePlugin(plugin);
        }
    }

    /** 锚点插入（置于目标行之后/之前）。 */
    record Insert(String plugin, Map<String, Object> config, String disabled, Anchor anchor)
            implements ConfigRowSpec {

        /** @throws NullPointerException/IllegalArgumentException plugin 或锚点非法时 */
        public Insert {
            plugin = requirePlugin(plugin);
            config = normalize(config);
            Objects.requireNonNull(anchor, "anchor");
        }
    }

    /** 插入锚点：target 插件行的之后/之前。 */
    record Anchor(String target, boolean before) {

        /** @throws NullPointerException/IllegalArgumentException target 非法时 */
        public Anchor {
            Objects.requireNonNull(target, "target");
            if (target.isEmpty()) {
                throw new IllegalArgumentException("anchor target must be non-empty");
            }
        }

        /** @return 置于 target 之后的锚点 */
        public static Anchor after(String target) {
            return new Anchor(target, false);
        }

        /** @return 置于 target 之前的锚点 */
        public static Anchor before(String target) {
            return new Anchor(target, true);
        }
    }

    private static String requirePlugin(String plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (plugin.isEmpty()) {
            throw new IllegalArgumentException("plugin must be non-empty");
        }
        return plugin;
    }

    private static Map<String, Object> normalize(Map<String, Object> config) {
        return config == null ? Map.of() : Map.copyOf(config);
    }
}
