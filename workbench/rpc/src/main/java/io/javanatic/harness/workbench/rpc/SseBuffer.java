package io.javanatic.harness.workbench.rpc;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/** 每连接有界编码帧队列；超帽即终结，EOF 则保留已入队帧。 */
final class SseBuffer {
    private static final int MAX_FRAMES = 64;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    sealed interface Result {}
    record Data(byte[] bytes) implements Result {}
    record Idle() implements Result {}
    record End() implements Result {}

    private enum State { OPEN, EOF, CLOSED }

    private final Deque<byte[]> queue = new ArrayDeque<>();
    private int bytes;
    private State state = State.OPEN;

    synchronized boolean offer(byte[] frame) {
        if (state != State.OPEN) {
            return false;
        }
        if (queue.size() == MAX_FRAMES || frame.length > MAX_BYTES - bytes) {
            close();
            return false;
        }
        queue.addLast(frame);
        bytes += frame.length;
        notifyAll();
        return true;
    }

    synchronized Result poll(Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        while (queue.isEmpty() && state == State.OPEN && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        if (!queue.isEmpty()) {
            byte[] frame = queue.removeFirst();
            bytes -= frame.length;
            return new Data(frame);
        }
        return state == State.OPEN ? new Idle() : new End();
    }

    synchronized void finish() {
        if (state == State.OPEN) {
            state = State.EOF;
            notifyAll();
        }
    }

    synchronized void close() {
        state = State.CLOSED;
        queue.clear();
        bytes = 0;
        notifyAll();
    }
}
