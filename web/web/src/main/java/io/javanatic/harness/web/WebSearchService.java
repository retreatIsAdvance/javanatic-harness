package io.javanatic.harness.web;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.AbortSignal;

/**
 * 搜索能力：一次调用一个 query，返回可引用的来源集合。Provider 可替换
 * （本迭代实装 DeepSeek 原生搜索）；消费者只依赖本接口。
 */
public interface WebSearchService {

    /** 本服务的服务键。 */
    ServiceKey<WebSearchService> KEY = new ServiceKey<>("web-search");

    /** 稳定 provider id（如 {@code deepseek}）。 */
    String id();

    /** 人读标识（含模型名时搜索结果的 provider 行可直接外显）。 */
    String displayName();

    /** 廉价本地可用性检查（如凭据在场），绝不联网。 */
    boolean available();

    /**
     * 执行一次搜索。
     *
     * @param request 单 query + 结果上限
     * @param signal 取消信号；取消时抛 {@code AbortedException}，不包装
     * @return 可引用来源（按 provider 返回序去重后截断）与可选摘要
     * @throws WebException 端点失败、结果不可解析或未返回原生搜索结果块
     */
    WebSearchResult search(WebSearchRequest request, AbortSignal signal) throws WebException;
}
