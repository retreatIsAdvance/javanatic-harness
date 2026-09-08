package io.javanatic.harness.boot;

import io.javanatic.harness.kernel.config.ConfigRowSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行合并器（07 §2,纯函数）:bundle 行 Include 语义叠加,patch 行按动作
 * (Include 宽松替换或追加 / Replace 严格 / Remove / Insert 锚点)逐层作用;
 * 目标缺失、同 id 双行 fail loud。矛盾动作组合已在装载边界拦截,此处无防御。
 */
public final class RowComposer {

    private RowComposer() {
    }

    /**
     * @param base   当前累积行序
     * @param patch  本层 patch 行
     * @return 作用后的新行序(输入不修改)
     * @throws IllegalStateException Replace/Remove/Insert 目标不存在时
     */
    public static List<ConfigRowSpec> apply(List<ConfigRowSpec> base, List<ConfigRowSpec> patch) {
        List<ConfigRowSpec> rows = new ArrayList<>(base);
        for (ConfigRowSpec spec : patch) {
            switch (spec) {
                case ConfigRowSpec.Remove remove -> removeById(rows, remove.plugin(), true);
                case ConfigRowSpec.Replace replace -> replaceById(rows, replace);
                case ConfigRowSpec.Insert insert -> insert(rows, insert);
                case ConfigRowSpec.Include include -> includeRow(rows, include);
            }
        }
        rejectDuplicates(rows);
        return rows;
    }

    private static void includeRow(List<ConfigRowSpec> rows, ConfigRowSpec.Include include) {
        int at = indexOf(rows, include.plugin());
        if (at >= 0) {
            rows.set(at, include);
        } else {
            rows.add(include);
        }
    }

    private static void replaceById(List<ConfigRowSpec> rows, ConfigRowSpec.Replace replace) {
        int at = indexOf(rows, replace.plugin());
        if (at < 0) {
            throw new IllegalStateException("patch Replace target not present: " + replace.plugin());
        }
        rows.set(at, replace);
    }

    private static void insert(List<ConfigRowSpec> rows, ConfigRowSpec.Insert insert) {
        String target = insert.anchor().target();
        int at = indexOf(rows, target);
        if (at < 0) {
            throw new IllegalStateException("patch Insert anchor not present: " + target);
        }
        rows.add(insert.anchor().before() ? at : at + 1, insert);
        rejectDuplicates(rows);
    }

    private static void removeById(List<ConfigRowSpec> rows, String plugin, boolean required) {
        int at = indexOf(rows, plugin);
        if (at < 0) {
            if (required) {
                throw new IllegalStateException("patch Remove target not present: " + plugin);
            }
            return;
        }
        rows.remove(at);
    }

    private static int indexOf(List<ConfigRowSpec> rows, String plugin) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).plugin().equals(plugin)) {
                return i;
            }
        }
        return -1;
    }

    private static void rejectDuplicates(List<ConfigRowSpec> rows) {
        Map<String, ConfigRowSpec> seen = new LinkedHashMap<>();
        for (ConfigRowSpec row : rows) {
            ConfigRowSpec prev = seen.put(row.plugin(), row);
            if (prev != null) {
                throw new IllegalStateException("duplicate plugin row: " + row.plugin());
            }
        }
    }
}
