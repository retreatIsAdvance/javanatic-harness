package io.javanatic.harness.delegation;

import io.javanatic.harness.kernel.brand.Id;

/** 委派品牌标记：Id&lt;Delegation&gt; 的品牌类型（见 kernel.brand.Id）。 */
public interface Delegation {

    /**
     * 委派 id 工厂；raw 由 Provider 生成（确定性前缀 + 随机尾），空串拒绝。
     * @param raw 委派 id 原文
     * @return 品牌化委派 id
     */
    static Id<Delegation> newId(String raw) {
        return new Id<>(raw);
    }
}
