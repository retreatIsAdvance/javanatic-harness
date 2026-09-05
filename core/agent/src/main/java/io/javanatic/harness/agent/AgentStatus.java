package io.javanatic.harness.agent;

/** agent 驱动状态：IDLE（无 driver）或 RUNNING（turn 驱动/维护任务占用中）。 */
public enum AgentStatus {
    IDLE, RUNNING
}
