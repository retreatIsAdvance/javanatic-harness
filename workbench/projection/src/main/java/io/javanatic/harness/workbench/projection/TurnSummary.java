package io.javanatic.harness.workbench.projection;

import io.javanatic.harness.session.message.TokenUsage;

/**
 * turns 视图:一轮的摘要。
 *
 * <p>轮边界来自 turn/start 与 turn/end;只有携带轮号的事件参与统计
 * (user/message、request/header 等无轮号事件不归属任何轮)。无 TurnStart
 * 的轮按首个携带该轮号的事件时间开轮;无 TurnEnd 的轮 open=true。
 * 同一轮出现多条 TurnStart/TurnEnd(畸形日志)时:开始时间取最后一条
 * TurnStart,结束取最后一条 TurnEnd——投影如实呈现,不做校正。
 *
 * @param turn      轮号
 * @param open      是否未关闭(无 turn/end)
 * @param startTime 轮开始时间(Unix epoch 毫秒;TurnStart.time,隐式开轮为首个事件时间)
 * @param endTime   轮结束时间(Unix epoch 毫秒);null = 未关闭
 * @param endReason 结束原因渲染串(completed / aborted:&lt;cause&gt; /
 *                  error:&lt;KIND&gt;:&lt;message&gt; / 扩展变体简单类名);null = 未关闭
 * @param steps     该轮内 step/start 次数
 * @param toolCalls 该轮内 tool/call 次数
 * @param usage     该轮 assistant/message 的 token 计量合计;null = 全轮未报告
 */
public record TurnSummary(int turn, boolean open, long startTime,
                          Long endTime, String endReason,
                          int steps, int toolCalls, TokenUsage usage) {
}
