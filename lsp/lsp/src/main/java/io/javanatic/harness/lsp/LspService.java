package io.javanatic.harness.lsp;

import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.llm.AbortSignal;

/**
 * 单项目三能力 seam。同步阻塞，可在虚拟线程调用；Provider 隐藏进程、同步与通知缓存。
 * 成功观察必须完成请求窗口并复验 JH 输入锚；hash 不是 LS 分析来源或完成证明。
 * 后台通知只能更新候选；Consumer 落账后才可让模型看见，回放不启动进程或重查源码。
 * 取消原样抛 AbortedException；进程/协议/读取错误抛 LspException，不能降级为空结果。
 */
public interface LspService extends AutoCloseable {
    ServiceKey<LspService> KEY = new ServiceKey<>("lsp");

    /**
     * 拉取客户端诊断观察；空结果仅称“拉取时点无诊断”。
     * @param request 文档与总期限/等待窗
     * @param signal 本次请求取消信号，不默认关闭整个项目
     * @return 四态结果；Observed 来源只能为 QUIET_WINDOW 或 PUBLISHED_DIAGNOSTICS
     */
    LspResult<LspDiagnostic> diagnostics(LspQuery request, AbortSignal signal);

    /**
     * 真实语义定义请求；不能用文本搜索或通知沉默合成结果。
     * @param request 文档、UTF-16 位置与同一观察预算
     * @param signal 本次请求取消信号
     * @return 四态结果；Observed 必须有 REQUEST_RESPONSE 依据
     */
    LspResult<LspLocation> definition(LspPositionQuery request, AbortSignal signal);

    /**
     * 真实语义引用请求，包含声明；空列表也须有成功的请求响应。
     * @param request 文档、UTF-16 位置与同一观察预算
     * @param signal 本次请求取消信号
     * @return 四态结果；Observed 必须有 REQUEST_RESPONSE 依据
     */
    LspResult<LspLocation> references(LspPositionQuery request, AbortSignal signal);

    /**
     * 仅拥有 scope 调用，借用者不得关闭。幂等、有界、拒新请求、清 pending 与旧缓存。
     * shutdown/exit 期间继续应答已解析 server 请求；失败 TERM→KILL，wait 后复验专属组，
     * EPERM 不视为消失。回收不成功抛 CLOSE_FAILED；停止不回滚 LS 文件写入。
     * 产品验收须 ≥3 次启停、忽略 TERM 的 KILL 路径与退出请求应答，不以值测试替代。
     */
    @Override
    void close();
}
