package io.javanatic.harness.sandbox.sandbox;

/**
 * 本后端在此宿主上的强制完备度。PARTIAL = 后端或旧内核 ABI 管不住承诺的
 * 每一种文件效果——要求绝对边界的调用方不得将其当 FULL 使用。
 */
public enum SandboxEnforcement {

    /** 承诺的文件效果全部由内核强制。 */
    FULL,

    /**
     * 部分强制：首用是 windows-acl（可写面 = 主机上一切低完整性标签对象，超出工作区
     * 树）；Landlock ABI 级别降档为同类预留。
     */
    PARTIAL
}
