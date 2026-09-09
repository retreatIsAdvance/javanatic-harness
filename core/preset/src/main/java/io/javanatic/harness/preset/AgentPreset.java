package io.javanatic.harness.preset;

import io.javanatic.harness.kernel.config.ConfigRowSpec;

import java.util.List;

/**
 * 一个 preset:名字 + 一组 Include 语义行(06 §6)。preset 不承载 patch 动作
 * (replace/remove/insert 出现在 preset 中即 fail loud——那是组合层的事)。
 *
 * @param name        preset id(目录名)
 * @param description 人读描述
 * @param rows        挂载行(仅 Include)
 */
public record AgentPreset(String name, String description, List<ConfigRowSpec.Include> rows) {

    /** @throws NullPointerException 任一字段为 null 时 */
    public AgentPreset {
        java.util.Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        rows = List.copyOf(rows);
    }
}
