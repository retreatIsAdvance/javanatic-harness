package io.javanatic.harness.jobs;

/** 任务失败成因（Failed 态携带），与退出码正交：SPAWN_FAILED / PIPE_BROKEN 无有效退出码（记 -1）。 */
public enum JobFailureReason {
    /** 进程未能启动（解释器缺失、权限等），任务从未运行。 */
    SPAWN_FAILED,
    /** 超时被 Provider 终止（TERM→KILL 语义归 Provider），退出码取实际可观测值。 */
    TIMED_OUT,
    /** 输出管道破裂（进程写端关闭或读取失败），输出捕获不完整。 */
    PIPE_BROKEN
}
