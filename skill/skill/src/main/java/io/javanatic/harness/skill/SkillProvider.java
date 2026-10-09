package io.javanatic.harness.skill;

import java.util.List;
import java.util.Optional;

/**
 * 技能供应方：按根发现技能并按名装载全文。{@link #catalog} 每轮重查（发现节奏 =
 * 轮首，it26 T3——provider 不持缓存，快照语义由本方法返回值承载）；
 * {@link #load} 只在 provider 自己声明的候选上被调到。
 */
public interface SkillProvider {

    /** provider 标识（kebab-case，如 {@code skill-local}）；注册表内唯一，重复 fail loud。 */
    String id();

    /**
     * 当前目录快照（每轮重查）。实现方对损坏/非法条目跳过并告警、不抛（it26 内容 2，
     * dsh 同款容错——坏条目不杀轮）；返回条目允许跨 priority 重复同名（合并归注册表）。
     */
    List<SkillCandidate> catalog();

    /**
     * 装载全文；实现方保证只对自己 catalog() 出现过的名字被调到。
     * 名字消失（目录清空）时返回空——调用方按「已退役」处理，不视为错误。
     */
    Optional<SkillDefinition> load(String name);
}
