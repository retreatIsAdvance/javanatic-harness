package io.javanatic.harness.sandbox.sandbox;

/**
 * 同机后端可用性的查询结果（{@link SandboxProvider#backendStatus()}）：
 * 受限档 fail-closed 的部署期可见形态——verify/preflight 用它把
 * 「首调用才炸」提前成组合期的点名预警。探针与 confine 共用同一份
 * 首探缓存：不另做执行,结论一致。
 */
public sealed interface BackendStatus {

    /** 首个候选探针可用（点名后端 id）。 */
    record Ready(String backend) implements BackendStatus {}

    /** 本平台无同机后端（平台链为空——点名平台）。 */
    record NoBackend(String platform) implements BackendStatus {}

    /** 平台有候选但探针全部失败（点名平台与逐候选失败明细）。 */
    record ProbeFailed(String platform, String detail) implements BackendStatus {}
}
