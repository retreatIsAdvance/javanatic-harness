package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 帧层(停 1-S2)：请求解析 → 方法派发 → 结果/错误序列化。
 * 纯函数(String → String)，无状态无 IO，handler 是唯一注入面。
 * 请求型错与协议违例归本层(-32700/-32600/-32602 结构面)；域码与 -32601
 * 由 handler 经 {@link RpcException} 上抛、本层原样翻译；其余
 * RuntimeException 兜底 -32603，堆栈不外泄。v1 请求-响应 only：batch
 * 数组与 id null 通知一律 -32600。
 */
public final class JsonRpcWire {

    /** 方法派发面：method + params → result；域错误抛 {@link RpcException}。 */
    @FunctionalInterface
    public interface Handler {

        /** 执行方法;params 恒非 null(缺省 params 以 NullNode 传入)。 */
        JsonNode call(String method, JsonNode params);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 处理单个 JSON-RPC 2.0 请求体;恒返回合法 JSON 响应(含错误对象)。 */
    public String handle(String body, Handler handler) {
        final JsonNode request;
        try {
            request = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return serialize(new RpcResponse(null, null, new RpcError(-32700, "parse error")));
        }
        if (request == null || !request.isObject()) {
            return serialize(new RpcResponse(null, null, new RpcError(-32600, "invalid request")));
        }
        JsonNode id = request.get("id");
        if (id == null || id.isNull() || !(id.isNumber() || id.isTextual())) {
            return serialize(new RpcResponse(null, null, new RpcError(-32600, "invalid request")));
        }
        JsonNode version = request.get("jsonrpc");
        if (version == null || !version.isTextual() || !"2.0".equals(version.asText())) {
            return serialize(new RpcResponse(id, null, new RpcError(-32600, "invalid request")));
        }
        JsonNode method = request.get("method");
        if (method == null || !method.isTextual()) {
            return serialize(new RpcResponse(id, null, new RpcError(-32600, "invalid request")));
        }
        JsonNode params = request.get("params");
        if (params != null && !params.isObject()) {
            return serialize(new RpcResponse(id, null, new RpcError(-32602, "invalid params")));
        }
        try {
            RpcRequest parsed = new RpcRequest(method.asText(),
                    params == null ? MAPPER.nullNode() : params, id);
            JsonNode result = handler.call(parsed.method(), parsed.params());
            return serialize(new RpcResponse(id, result, null));
        } catch (RpcException e) {
            return serialize(new RpcResponse(id, null, new RpcError(e.code(), e.getMessage(), e.data())));
        } catch (RuntimeException e) {
            return serialize(new RpcResponse(id, null, new RpcError(-32603, "Internal error")));
        }
    }

    private String serialize(RpcResponse response) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", response.id());
        if (response.error() != null) {
            ObjectNode error = node.putObject("error");
            error.put("code", response.error().code());
            error.put("message", response.error().message());
            if (response.error().data() != null) {
                error.set("data", response.error().data());
            }
        } else {
            node.set("result", response.result());
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreachable: JsonNode serialization", e);
        }
    }
}
