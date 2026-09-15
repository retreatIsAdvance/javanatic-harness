package io.javanatic.harness.examples.headless;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * REPL 审批输入（补充 6「REPL 审批合流」）：REPL 模式下真 stdin 的唯一读者是
 * REPL 行循环；组合出的 {@code ApprovalPrompt.stdin()} 经 System.in 读本流——
 * 裁决行由行循环 {@link #forward} 转交（有 read 阻塞等待才收下，否则行归
 * 命令面/模型），不引入第二个 stdin 读者。行循环退出（EOF 或 /exit）后
 * {@link #close}：未决裁决读到 -1 —— 拒绝语义（与既有一致）。
 */
final class ReplApprovalInput extends InputStream {

    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();
    private byte[] pending;
    private int pendingOffset;
    private boolean awaiting;
    private boolean closed;

    /** 行循环转交：有等待中的裁决则收下该行并返回 true；否则返回 false（行归 REPL）。 */
    synchronized boolean forward(String line) {
        if (!awaiting || closed) {
            return false;
        }
        ready.add((line + "\n").getBytes(StandardCharsets.UTF_8));
        notifyAll();
        return true;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
        if (len == 0) {
            return 0;
        }
        if (pending == null) {
            pending = awaitLine();
            pendingOffset = 0;
            if (pending == null) {
                return -1;
            }
        }
        int n = Math.min(len, pending.length - pendingOffset);
        System.arraycopy(pending, pendingOffset, b, off, n);
        pendingOffset += n;
        if (pendingOffset == pending.length) {
            pending = null;
            pendingOffset = 0;
        }
        return n;
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    /** 行循环退出：未决裁决得 EOF（拒绝语义）。 */
    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private byte[] awaitLine() {
        try {
            while (ready.isEmpty() && !closed) {
                awaiting = true;
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            awaiting = false;
        }
        return ready.poll();
    }
}
