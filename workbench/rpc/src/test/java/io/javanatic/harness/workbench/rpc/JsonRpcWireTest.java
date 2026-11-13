package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON-RPC 2.0 帧契约（停 1-S2 T1–T6）：请求解析 → 方法派发 → 结果/错误
 * 序列化。wire 是纯函数（String → String），handler 是唯一注入面；请求
 * 型错与协议违例归 wire（-32700/-32600/-32602 结构面），域码与 -32601
 * 由 handler 经 {@link RpcException} 上抛、wire 原样翻译。
 */
class JsonRpcWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcWire wire = new JsonRpcWire();

    @Test
    void echoesIdAndSerializesResult() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":{},\"id\":7}",
                (method, params) -> MAPPER.createObjectNode().put("pong", true));

        JsonNode response = MAPPER.readTree(out);
        assertThat(response.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.get("id").asInt()).isEqualTo(7);
        assertThat(response.get("result").get("pong").asBoolean()).isTrue();
    }

    @Test
    void stringIdsEchoVerbatim() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":\"abc\"}",
                (method, params) -> MAPPER.createObjectNode().put("ok", true));

        assertThat(MAPPER.readTree(out).get("id").asText()).isEqualTo("abc");
    }

    @Test
    void malformedJsonYieldsParseError() throws Exception {
        String out = wire.handle("{not json", (method, params) -> null);

        JsonNode response = MAPPER.readTree(out);
        assertThat(response.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.get("id").isNull()).isTrue();
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void scalarRequestBodyYieldsInvalidRequest() throws Exception {
        String out = wire.handle("42", (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void batchArrayYieldsInvalidRequest() throws Exception {
        String out = wire.handle("[]", (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void notificationWithNullIdYieldsInvalidRequest() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":null}",
                (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void requestWithoutIdFieldYieldsInvalidRequest() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\"}", (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void missingMethodYieldsInvalidRequest() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"id\":1}", (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void wrongJsonrpcVersionYieldsInvalidRequest() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"1.0\",\"method\":\"m\",\"id\":1}",
                (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void nonObjectParamsYieldInvalidParams() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":[1],\"id\":1}",
                (method, params) -> null);

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void rpcExceptionTranslatesToErrorObjectWithData() throws Exception {
        JsonNode data = MAPPER.createObjectNode().put("session", "abc");
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":7}",
                (method, params) -> {
                    throw new RpcException(-32001, "no such session", data);
                });

        JsonNode error = MAPPER.readTree(out).get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32001);
        assertThat(error.get("message").asText()).isEqualTo("no such session");
        assertThat(error.get("data").get("session").asText()).isEqualTo("abc");
        assertThat(MAPPER.readTree(out).get("id").asInt()).isEqualTo(7);
    }

    @Test
    void rpcExceptionWithStandardCodePassesThroughVerbatim() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"nope\",\"id\":1}",
                (method, params) -> {
                    throw new RpcException(-32601, "method not found");
                });

        assertThat(MAPPER.readTree(out).get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void uncheckedExceptionTranslatesToInternalErrorWithoutLeak() throws Exception {
        String out = wire.handle("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":1}",
                (method, params) -> {
                    throw new IllegalStateException("boom");
                });

        JsonNode error = MAPPER.readTree(out).get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32603);
        assertThat(out).doesNotContain("boom");
        assertThat(out).doesNotContain("IllegalStateException");
    }
}
