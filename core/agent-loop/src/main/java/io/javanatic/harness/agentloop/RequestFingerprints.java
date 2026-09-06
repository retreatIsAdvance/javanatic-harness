package io.javanatic.harness.agentloop;

import io.javanatic.harness.llm.ToolSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * R1 请求指纹的纯函数(loop 落账与回放验证共用同一函数——否则回放验证的
 * 不是生产行为)。schema 指纹原料:名称序 + 定界符拼接,可复算。
 */
public final class RequestFingerprints {

    private RequestFingerprints() {
    }

    /** @return 文本的 SHA-256 十六进制 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @return 工具 schema 清单的确定性指纹原料(同名序 + 定界符拼接) */
    public static String toolSchemaFingerprint(List<ToolSchema> schemas) {
        StringBuilder sb = new StringBuilder();
        for (ToolSchema schema : schemas) {
            sb.append(schema.name()).append('\n')
                .append(schema.description()).append('\n')
                .append(schema.parametersJson()).append('\u0000');
        }
        return sb.toString();
    }
}
