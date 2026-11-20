package io.javanatic.harness.workbench.rpc;

/**
 * 写面 seam（it38 停 1，T1 精度：接口进 rpc、实现进装配面）：四动词直接对应
 * JSON-RPC 写方法组，实现方（examples/headless）复用既有执行面
 * （{@code core/agent} + AppBoot 组合），R2 不开新通道。
 *
 * <p>错误语义：实现方按域码抛 {@link RpcException}——
 * -32001 会话不存在 / -32006 在途轮拒绝 send / -32007 审批请求不存在 /
 * -32008 审批已结算 / -32009 审批已过期。本接口只搬运，不做等待界判断：
 * 审批 300s fail-closed 归审批等待面（ApprovalPrompt 口径），不在此放宽。
 *
 * <p>launch 语义（停 1 契约）：{@code start}/{@code send} 只负责**发起**轮，
 * 发起成功即返回（会话 id / ok），不等待轮终局——轮内失败、审批中继、流式
 * 输出都经事件面观察，绝不把轮结果折叠进 RPC 错误。{@code cancel} 对无
 * 在途轮的会话为幂等空操作（UI 可随时按），未知会话仍 -32001。
 *
 * <p>写面关闭时（`--web-write` 缺省）不注入实现：handler 对四方法一律
 * -32601，行为与 0.5.0 只读面完全一致。
 */
public interface ChatControl {

    /**
     * 新建会话并**发起**首轮（不等待轮终局）。
     *
     * @param text 用户消息（非空，参数校验在 handler）
     * @return 新会话 id
     */
    String start(String text);

    /**
     * 对既有会话**发起**下一轮 user message（`--resume` 等价；exit 5 等答复的答复通道）。
     * 在途轮重复 send → {@link RpcException}(-32006)；未知会话 → -32001。
     *
     * @param session 会话 id
     * @param text    用户消息（非空）
     */
    void send(String session, String text);

    /**
     * 取消在途轮（it18 协作取消：aborted 落账、exit 4 语义）。
     * 无在途轮 → 幂等空操作；未知会话 → -32001。
     *
     * @param session 会话 id
     */
    void cancel(String session);

    /**
     * 结算一次审批请求（一次性；server 发号，UI 词表只有允许/拒绝）。
     * 迟到/重复 → {@link RpcException}(-32009/-32008)；未知 → -32007。
     *
     * @param requestId 审批请求 id（server 发号的 UUID）
     * @param allow     true = 允许，false = 拒绝
     */
    void respondApproval(String requestId, boolean allow);
}
