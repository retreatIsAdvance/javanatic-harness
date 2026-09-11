package io.javanatic.harness.sandbox.sandbox;

/**
 * 请求的受限模式在宿主上无可强制后端。fail-closed：宁可拒绝执行也不
 * 静默透传（executor 将消息转 error result，结构化 code 随词表外显）。
 */
public final class SandboxUnavailableException extends RuntimeException {

    /** 结构化错误 code（tool/result 与消费方据此区分「缺围栏」与「命令失败」）。 */
    public static final String CODE = "SANDBOX_UNAVAILABLE";

    /**
     * @param mode   请求的受限模式
     * @param detail 探针/启动失败细节（可 null）
     */
    public SandboxUnavailableException(SandboxMode mode, String detail) {
        super("sandbox mode \"" + mode.wire() + "\" is requested but no sandbox backend is "
            + "usable on this host; refusing to run the command unconfined"
            + (detail == null ? "" : " — " + detail));
    }
}
