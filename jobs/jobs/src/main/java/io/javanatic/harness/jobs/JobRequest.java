package io.javanatic.harness.jobs;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 后台任务启动请求。超时自进程启动计，覆盖整个后台生命周期，不因输出读取重置；
 * 到时由 Provider 终止进程并落 Failed(TIMED_OUT)，不静默放行。
 * 工作目录须绝对路径；与提示词 cwd 的同源对齐由 Consumer 装配面校验，不在本契约。
 * 输出上限按捕获字节计（UTF-8），超限停止捕获并标记 outputTruncated，不终止任务。
 */
public record JobRequest(String command, Path workingDirectory, Duration timeout, long outputCapBytes) {

    /** 启动即校验：坏请求在装配点失败，不进 Provider。 */
    public JobRequest {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must be non-blank");
        }
        if (workingDirectory == null || !workingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("workingDirectory must be absolute");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (outputCapBytes <= 0) {
            throw new IllegalArgumentException("outputCapBytes must be positive");
        }
    }
}
