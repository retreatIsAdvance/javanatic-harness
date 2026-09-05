package io.javanatic.harness.agent;

/** Inbox 投递目标：NEXT_TURN（普通排队，各成一轮）或 NEXT_STEP（steering + 注入，下次认领即消费）。 */
public enum InboxTarget {
    NEXT_TURN, NEXT_STEP
}
