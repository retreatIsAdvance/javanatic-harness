package io.javanatic.harness.workbench.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 错误词表(停 1-S2)：code + message 恒有;data 仅域码携带。
 */
public record RpcError(int code, String message, JsonNode data) {

    public RpcError(int code, String message) {
        this(code, message, null);
    }
}
