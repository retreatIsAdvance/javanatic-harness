package io.javanatic.harness.web;

/**
 * 网络资料获取的稳定错误码（机器可路由，模型面与审计面同词表）。
 *
 * <p>取消不是错误：调用方取消时穿透 {@code AbortedException}，不产生本枚举。
 * 非 2xx 是抓取结果（状态码在 {@link WebFetchResult#statusCode()}），不是错误。
 */
public enum WebErrorCode {

    /** URL 解析失败、非 https、含 userinfo、超长或 IDN 非法。 */
    WEB_INVALID_URL,

    /** 主机解析结果或字面 IP 落在非公网地址（含多答案中任一非公网）。 */
    WEB_BLOCKED_ADDRESS,

    /** 重定向超跳、降级到 http、Location 非法或相对解析失败。 */
    WEB_REDIRECT_BLOCKED,

    /** 内容类型不在白名单、charset 标签不支持，或 Content-Encoding 非 identity。 */
    WEB_UNSUPPORTED_CONTENT_TYPE,

    /** 声明体长超过上限（流式增长超限按截断处理，不产生本码）。 */
    WEB_TOO_LARGE,

    /** 总截止时间或连接超时。 */
    WEB_TIMEOUT,

    /** 解析/连接/TLS/HTTP 协议畸形或截断。 */
    WEB_NETWORK_ERROR,

    /** 未挂载对应 provider，或 provider 缺少凭据/配置不可用。 */
    WEB_PROVIDER_UNAVAILABLE,

    /** provider 端点失败、结果不可解析，或未返回原生搜索结果块。 */
    WEB_PROVIDER_ERROR
}
