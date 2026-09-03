package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.ToolUseBlock;

import java.util.List;

/** 唯一执行路径（R2）：模型发起的副作用只经此处，且结构性留痕。 */
public interface ToolExecutor {

    /** 本服务的服务键。 */
    ServiceKey<ToolExecutor> KEY = new ServiceKey<>("toolExecutor");

    /**
     * 执行一批模型工具调用：落账 tool/call → 逐个执行（并行）→ 落账 tool/result。
     * 返回与输入同序的结果信封列表。错误即数据（error result）；仅
     * {@link io.javanatic.harness.llm.AbortedException} 传播（取消收敛 turn）。
     *
     * @param session 落账目标会话（executor 跨会话共享，会话是参数而非字段）
     * @param signal  取消信号
     */
    List<LoggedEvent<ToolResultEvent>> execute(List<ToolUseBlock> calls, Session session,
                                               int turn, int step, AbortSignal signal);
}
