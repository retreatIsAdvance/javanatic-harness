package io.javanatic.harness.agent;

import io.javanatic.harness.session.message.UserMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * agent 拥有的 pending 消息投影：nextTurn（普通排队）与 nextStep（steering + 注入）。
 * 修改是进程内的（durable inbox 事件随持久化切片）；线程安全经方法级同步。
 */
public final class Inbox {

    private final Deque<UserMessage> nextTurn = new ArrayDeque<>();
    private final Deque<UserMessage> nextStep = new ArrayDeque<>();

    /**
     * 追加到指定队列尾部。
     *
     * @param target  投递目标
     * @param message 消息
     * @throws NullPointerException 任一参数为 null 时
     */
    public synchronized void append(InboxTarget target, UserMessage message) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        queue(target).addLast(message);
    }

    /**
     * 认领一批：turn 边界取一条普通排队（一条 ordinary 消息驱动一轮），
     * step 边界排空全部 steering/注入。纯删除 splice，不产生通知。
     *
     * @param context 认领边界
     * @return 被认领的消息（按入队序）；空队列返回空列表
     */
    public synchronized List<UserMessage> claim(InboxTarget context) {
        return switch (context) {
            case NEXT_TURN -> {
                UserMessage head = nextTurn.pollFirst();
                yield head == null ? List.<UserMessage>of() : List.of(head);
            }
            case NEXT_STEP -> drain(nextStep);
        };
    }

    /** 取消（keepInbox=false）时清空两队列。 */
    public synchronized void clear() {
        nextTurn.clear();
        nextStep.clear();
    }

    /** 普通排队快照（入队序）。 */
    public synchronized List<UserMessage> nextTurn() {
        return List.copyOf(nextTurn);
    }

    /** steering/注入快照（入队序）。 */
    public synchronized List<UserMessage> nextStep() {
        return List.copyOf(nextStep);
    }

    private Deque<UserMessage> queue(InboxTarget target) {
        return target == InboxTarget.NEXT_TURN ? nextTurn : nextStep;
    }

    private static List<UserMessage> drain(Deque<UserMessage> queue) {
        if (queue.isEmpty()) {
            return List.of();
        }
        List<UserMessage> drained = new ArrayList<>(queue);
        queue.clear();
        return drained;
    }
}
