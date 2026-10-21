package io.javanatic.harness.web;

import java.util.Objects;

/**
 * 一次搜索请求：单 query + 结果上限。多 query 留给独立 schema 变更
 * （ValueSchema 无数组界限语义，it28 不做）。
 *
 * @param query 搜索词
 * @param maxResults 返回来源上限（正数；seam 负责截断）
 */
public record WebSearchRequest(String query, int maxResults) {

    /** @throws IllegalArgumentException query 为空或 maxResults 非正时 */
    public WebSearchRequest {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive: " + maxResults);
        }
    }
}
