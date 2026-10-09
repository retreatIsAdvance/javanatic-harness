package io.javanatic.harness.skill.tool;

import io.javanatic.harness.session.event.ExtensionEvent;

import java.util.List;
import java.util.Objects;

/**
 * 技能目录账（log-only 扩展事件，it26 内容 3）：快照 = 当轮目录的逐条
 * (name / 描述截断 / 来源根)。变更落账；无技能不落事件；曾有过清空落
 * 空事件退役（dsh 空目录语义）。ignorable=false：目录是提示段与装载
 * 的事实源，静默丢弃会毁 R1 重建（plan/mode 同款理据）。
 */
public record SkillCatalogEvent(long time, List<Entry> entries) implements ExtensionEvent {

    @Override
    public String type() {
        return "skill/catalog";
    }

    @Override
    public boolean ignorable() {
        return false;
    }

    public SkillCatalogEvent {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /** 目录条目：描述已按配置上限截断（缺省 500），来源根模型可见、可解释。 */
    public record Entry(String name, String description, String source) {

        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(source, "source");
        }
    }
}
