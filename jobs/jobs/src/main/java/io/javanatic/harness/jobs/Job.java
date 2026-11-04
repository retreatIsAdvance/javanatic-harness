package io.javanatic.harness.jobs;

import io.javanatic.harness.kernel.brand.Id;

/** 任务品牌标记：Id&lt;Job&gt; 的品牌类型（见 kernel.brand.Id）。 */
public interface Job {

    /**
     * 任务 id 工厂；raw 由 Provider 生成（确定性前缀 + 随机尾），空串拒绝。
     * @param raw 任务 id 原文
     * @return 品牌化任务 id
     */
    static Id<Job> newId(String raw) {
        return new Id<>(raw);
    }
}
