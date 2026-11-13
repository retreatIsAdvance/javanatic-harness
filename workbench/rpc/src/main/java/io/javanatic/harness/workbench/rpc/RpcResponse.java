package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 响应词表(停 1-S2)：result 与 error 恰好其一非 null;id 回显请求 id,
 * 请求级错误(-32700/-32600)时可为 null。
 */
public record RpcResponse(JsonNode id, JsonNode result, RpcError error) {
}
