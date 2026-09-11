package io.javanatic.harness.shell.shell;

import java.time.Duration;

/**
 * 一次命令执行的结果。
 *
 * @param exitCode        进程退出码（信号终止为 128+signo）
 * @param stdout          标准输出（超上限截断）
 * @param stderr          标准错误（超上限截断）
 * @param duration        实际执行时长
 * @param outputTruncated 任一流达到输出上限被截断
 * @param sandboxDenied   非零退出且 stderr 命中本后端拒绝方言——是沙箱拒了
 *                        文件效果，不是命令自身失败（模型据此分辨两者）
 */
public record ShellResult(int exitCode, String stdout, String stderr, Duration duration,
                          boolean outputTruncated, boolean sandboxDenied) {
}
