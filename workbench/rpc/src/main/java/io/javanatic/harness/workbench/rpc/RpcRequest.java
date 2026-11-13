package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 请求词表(停 1-S2)：经 {@link JsonRpcWire} 结构校验后的合法请求载体。
 * id 恒非 null(number/string)；params 恒非 null(缺省 params 以 NullNode 记)。
 */
public record RpcRequest(String method, JsonNode params, JsonNode id) {
}
