package io.javanatic.harness.web;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.AbortSignal;

/**
 * 网页抓取能力：一次调用取回一个公网资源（阻塞语义，虚拟线程下安全）。
 *
 * <p>实现方必须把「安全取回」作为契约的一部分：只访问公网、不跟随越界
 * 重定向、有界读取；非 2xx 作为结果返回，不抛出。消费者只依赖本接口与
 * {@link WebFetchRequest}/{@link WebFetchResult}，不 import 任何 Provider。
 */
public interface WebFetchService {

    /** 本服务的服务键。 */
    ServiceKey<WebFetchService> KEY = new ServiceKey<>("web-fetch");

    /** 稳定 provider id（如 {@code http}）。 */
    String id();

    /** 人读标识（含实现形态，搜索面还带模型名）。 */
    String displayName();

    /** 廉价本地可用性检查，绝不联网。 */
    boolean available();

    /**
     * 取回一个 URL。
     *
     * @param request 请求（URL）
     * @param signal 取消信号；取消时抛 {@code AbortedException}，不包装
     * @return 最终 URL、状态码、分类后的正文与是否因上限截断
     * @throws WebException 安全取回或表示失败（含 URL/地址/重定向/内容/上限/超时）
     */
    WebFetchResult fetch(WebFetchRequest request, AbortSignal signal) throws WebException;
}
