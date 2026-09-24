package io.javanatic.harness.interaction.approval;

import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.tools.ApprovalService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次审批的交互通道：向人呈现请求并等待裁决。默认实现走 headless stdin
 * （只接受显式 y）；组合可注入回调（未来 UI/ACP 接管）。
 * 缺省必须是拒绝语义——EOF/非交互/空闲超时绝不放行。
 * 等待期间须感知取消：等待中取消抛 {@link AbortedException}（走取消收敛），
 * 不得把取消误判成否定裁决。
 */
@FunctionalInterface
public interface ApprovalPrompt {

    /** 非交互终端缺省空闲上限（it22 裁决 B）：有界等待后按拒绝——绝不无限挂起。 */
    Duration NON_INTERACTIVE_IDLE_TIMEOUT = Duration.ofSeconds(300);

    /**
     * @param request 审批请求
     * @param signal  取消信号（等待中的轮询/监听依据）
     * @return true 放行
     * @throws AbortedException 已取消或等待中被取消——调用方按取消收敛
     */
    boolean ask(ApprovalService.ApprovalRequest request, AbortSignal signal);

    /**
     * 有效空闲上限（单源：headless 行注入与 approval-ask 插件缺省同一口径）：
     * 显式秒数优先（>=0；0 = 不设限）；缺省按终端形态——交互终端不设限（真人可能
     * 思考很久），非交互终端 {@link #NON_INTERACTIVE_IDLE_TIMEOUT}。
     *
     * @param configuredSeconds 显式配置的秒数；<0 = 未配置
     * @param interactive       是否交互终端（stdin 为 terminal）
     */
    static Duration effectiveIdleTimeout(long configuredSeconds, boolean interactive) {
        if (configuredSeconds >= 0) {
            return Duration.ofSeconds(configuredSeconds);
        }
        return interactive ? Duration.ZERO : NON_INTERACTIVE_IDLE_TIMEOUT;
    }

    /**
     * headless stdin 默认:打印摘要到 stderr,从 stdin 读一行,仅 y/Y 放行。
     * 可轮询形状:阻塞读放伴生虚拟线程,本线程轮询信号(50ms 间隔)——取消即抛
     * {@link AbortedException},并经 {@link AbortSignal#onCancel} 中断读线程:
     * 可撤回通道(如 REPL 代理流)随即退出等待;不可中断的原流阻塞读留在原地
     * 直到其自行返回(虚拟线程,不碍 JVM 退出)。读结束未必是回答——撤出导致
     * 的结束不得误判为拒绝,取消优先。
     * 空闲上限(it22):{@code idleTimeout} 为正时轮询兼看期限,到期撤出等待并按
     * 拒绝(fail-closed),stderr 给可行动文案;0/负 = 不设限(交互终端缺省)。
     *
     * @param idleTimeout 空闲上限(0/负 = 不设限)
     */
    static ApprovalPrompt stdin(Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        boolean bounded = !idleTimeout.isZero() && !idleTimeout.isNegative();
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
            long deadlineNanos = bounded ? System.nanoTime() + idleTimeout.toNanos() : 0;
            try {
                while (reader.isAlive()) {
                    signal.checkAbort();
                    if (bounded && System.nanoTime() - deadlineNanos >= 0) {
                        // 撤出等待同取消形:代理流随即读到 -1;原流阻塞读留在原地(虚拟线程)
                        reader.interrupt();
                        System.err.printf("[approval] 等待超时（%s）:无人应答,按拒绝处理——非交互场景请用"
                            + " --approval=auto|deny;需要人闸请调 --approval-timeout=<秒>（0 = 不设限）%n",
                            boundText(idleTimeout));
                        return false;
                    }
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

    /** 上限的人读形:秒优先,亚秒给毫秒(超时文案不回显裸 Duration)。 */
    private static String boundText(Duration idleTimeout) {
        return idleTimeout.toSeconds() >= 1
            ? idleTimeout.toSeconds() + "s"
            : idleTimeout.toMillis() + "ms";
    }
}
