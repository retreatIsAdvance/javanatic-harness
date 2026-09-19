package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.tools.ApprovalService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次审批的交互通道：向人呈现请求并等待裁决。默认实现走 headless stdin
 * （只接受显式 y）；组合可注入回调（未来 UI/ACP 接管）。
 * 缺省必须是拒绝语义——EOF/非交互/超时绝不放行。
 * 等待期间须感知取消：等待中取消抛 {@link AbortedException}（走取消收敛），
 * 不得把取消误判成否定裁决。
 */
@FunctionalInterface
public interface ApprovalPrompt {

    /**
     * @param request 审批请求
     * @param signal  取消信号（等待中的轮询/监听依据）
     * @return true 放行
     * @throws AbortedException 已取消或等待中被取消——调用方按取消收敛
     */
    boolean ask(ApprovalService.ApprovalRequest request, AbortSignal signal);

    /**
     * headless stdin 默认:打印摘要到 stderr,从 stdin 读一行,仅 y/Y 放行。
     * 可轮询形状:阻塞读放伴生虚拟线程,本线程轮询信号(50ms 间隔)——取消即抛
     * {@link AbortedException},并经 {@link AbortSignal#onCancel} 中断读线程:
     * 可撤回通道(如 REPL 代理流)随即退出等待;不可中断的原流阻塞读留在原地
     * 直到其自行返回(虚拟线程,不碍 JVM 退出)。读结束未必是回答——撤出导致
     * 的结束不得误判为拒绝,取消优先。
     */
    static ApprovalPrompt stdin() {
        return (request, signal) -> {
            System.err.printf("[approval] allow %s %s? [y/N] ", request.toolName(), request.summary());
            AtomicReference<String> line = new AtomicReference<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    line.set(new BufferedReader(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine());
                } catch (IOException e) {
                    // 读失败:line 保持 null——拒绝语义(fail-closed,与改动前一致)
                }
            });
            signal.onCancel(reader::interrupt);
            long pollMillis = 50; // 取消轮询间隔:撤销响应时延上界
            try {
                while (reader.isAlive()) {
                    signal.checkAbort();
                    reader.join(pollMillis);
                }
                // 读结束未必是回答:撤出(取消)导致的结束不得误判为拒绝,取消优先
                signal.checkAbort();
            } catch (AbortedException e) {
                reader.interrupt();
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reader.interrupt();
                return false; // 非取消的中断:缺省拒绝(fail-closed)
            }
            String value = line.get();
            return value != null && value.trim().equalsIgnoreCase("y");
        };
    }
}
