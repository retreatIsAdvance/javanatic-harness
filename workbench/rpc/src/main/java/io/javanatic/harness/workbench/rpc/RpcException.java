package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * handler 域错误(停 1-S2)：code 由 {@link JsonRpcWire} 原样翻译进错误对象。
 * 标准码(-32601/-32602)与域码(-32001..-32004)共用同一通道;message 面向
 * 客户端、不含堆栈;data 仅域码携带(如会话 id)。
 */
public final class RpcException extends RuntimeException {

    private final int code;
    private final JsonNode data;

    public RpcException(int code, String message) {
        this(code, message, null);
    }

    public RpcException(int code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    /** JSON-RPC 错误码(标准码或域码)。 */
    public int code() {
        return code;
    }

    /** 可选错误数据(仅域码携带)。 */
    public JsonNode data() {
        return data;
    }
}
