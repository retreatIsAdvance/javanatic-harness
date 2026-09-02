package io.javanatic.harness.llm;

import java.util.stream.Stream;

/**
 * 单厂商适配器：把 JH 规范请求翻译为厂商 wire 调用，把厂商响应翻成
 * 统一 {@link StreamChunk} 阻塞流。认证、baseUrl、重试、SSE 解析全部
 * 在实现内部；流必须以 {@link StreamChunk.Finish} 结束。
 */
public interface LlmAdapter {

    /**
     * 流式调用。阻塞 Stream：provider 在自己的线程上生产，
     * consumer 的 forEach 阻塞读；try-with-resources 关闭即取消生产侧。
     *
     * @param config 路由与模型身份
     * @param request 厂商中立请求
     * @param signal  取消信号（生产循环轮询 checkAbort）
     * @return 以 Finish 结束的阻塞 chunk 流
     */
    Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal);
}
