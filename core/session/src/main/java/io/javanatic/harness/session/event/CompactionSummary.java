package io.javanatic.harness.session.event;

import io.javanatic.harness.session.message.TokenUsage;

/**
 * 压缩的审计事件(log-only):安全摘要投影 + 维护调用信封(provider/model/usage
 * ——摘要的一次性请求可从日志 + 代码重建,R1 对维护调用同样成立,dsh 先例)
 * + 被盖写的 surface 位置区间。摘要本体作为模型可见事实走配对的
 * user/message(surfaceOp=Replace)事件,本事件只做审计。
 *
 * @param turn          发起压缩的轮号
 * @param summary       摘要全文(安全投影)
 * @param provider      摘要调用路由的 provider
 * @param model         摘要调用路由的 model
 * @param usage         摘要调用的 token 计量(未报告为 null)
 * @param shadowedStart 被盖写区间首个 surface 节点 seq
 * @param shadowedEnd   被盖写区间末个 surface 节点 seq(位置区间,非数值区间)
 */
public record CompactionSummary(long time, int turn, String summary, String provider, String model,
                                TokenUsage usage, long shadowedStart, long shadowedEnd)
        implements SessionEvent {

    @Override public String type() { return "compaction/summary"; }

    @Override public boolean ignorable() { return true; }
}
