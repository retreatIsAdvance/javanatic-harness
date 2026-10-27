package io.javanatic.harness.web.search.deepseek;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * DeepSeek Anthropic 兼容 Messages 的线上形状（与 dsh
 * {@code packages/web/web-search-deepseek/src/provider.ts} 同形）：模型、生成上限、
 * 单条 user text 消息、单个原生搜索工具。查询词只进消息正文，绝不进 URL。
 */
final class DeepSeekWire {

    /** 固定 {@code anthropic-version} 头值。 */
    static final String API_VERSION = "2023-06-01";

    /** 线路标识（与抓取 provider 同版本口径）。 */
    static final String USER_AGENT = "javanatic-harness/0.3";

    /** 原生搜索工具类型。 */
    static final String SEARCH_TOOL_TYPE = "web_search_20250305";

    /** 原生搜索工具名。 */
    static final String SEARCH_TOOL_NAME = "web_search";

    /** 查询词前缀（dsh 同文案；模型据此触发原工具）。 */
    static final String QUERY_PREFIX = "Perform a web search for the query: ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeepSeekWire() {
    }

    /**
     * 构造一次搜索的请求体。
     *
     * @param config 已解析配置
     * @param query 搜索词
     * @return UTF-8 JSON 字节
     * @throws IllegalStateException 序列化失败（理论上不可达）时
     */
    static byte[] requestBody(DeepSeekSearchConfig config, String query) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("max_tokens", config.maxTokens());
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.putArray("content").addObject()
            .put("type", "text")
            .put("text", QUERY_PREFIX + query);
        root.putArray("tools").addObject()
            .put("type", SEARCH_TOOL_TYPE)
            .put("name", SEARCH_TOOL_NAME)
            .put("max_uses", config.maxUses());
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("web-search-deepseek: cannot serialize request body", e);
        }
    }
}
