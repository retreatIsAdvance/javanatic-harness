package io.javanatic.harness.skill;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.ServiceKey;

import java.util.Optional;

/**
 * 技能能力：provider 注册 + 目录快照 + 按名装载（it26 停 1 契约）。
 * 阻塞语义（虚拟线程下安全）；合并规则单条——priority 小者胜，同名同 priority
 * fail loud，遮蔽告警（可解释：catalog 条目带来源根）。
 */
public interface SkillService {

    /** 本服务的服务键。 */
    ServiceKey<SkillService> KEY = new ServiceKey<>("skill");

    /**
     * 合并全部已注册 provider 的当前目录快照：同名按 priority 归并（小者胜），
     * 结果按名排序。每次调用重查（provider 每轮重发现，本方法不做缓存）。
     */
    SkillCatalog catalog();

    /**
     * 按名装载胜出候选的全文：委托给提供该候选的 provider。名字不存在或已退役
     * 时返回空（不抛——目录变更是常态事实，非错误）。
     */
    Optional<SkillDefinition> load(String name);

    /**
     * 注册 provider；返回幂等撤销凭据（R3）。provider id 重复 fail loud（注册错误，
     * 不是目录事实）。
     *
     * @throws IllegalStateException provider id 已注册时
     */
    Disposable register(SkillProvider provider);
}
