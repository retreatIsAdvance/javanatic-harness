package io.javanatic.harness.llm.replay;

import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.llm.LlmAdapter;
import io.javanatic.harness.llm.LlmCallConfig;
import io.javanatic.harness.llm.LlmRequest;
import io.javanatic.harness.llm.StreamChunk;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 回放适配器：逐次返回脚本里的固定分块序列（每次调用消耗一个脚本）。
 * 内存脚本、无网络、无 key——keyless 测试的地基（10 §3）。
 */
public final class ReplayAdapter implements LlmAdapter {

    private final List<List<StreamChunk>> scripts;
    private final AtomicInteger callIndex = new AtomicInteger();

    /** @param scripts 每次调用依次回放的脚本（非空） */
    public ReplayAdapter(List<List<StreamChunk>> scripts) {
        this.scripts = List.copyOf(scripts);
    }

    @Override
    public Stream<StreamChunk> stream(LlmCallConfig config, LlmRequest request, AbortSignal signal) {
        Objects.requireNonNull(signal, "signal");
        int index = callIndex.getAndIncrement();
        if (index >= scripts.size()) {
            throw new IllegalStateException(
                "replay script exhausted after " + scripts.size() + " call(s)");
        }
        Iterator<StreamChunk> chunks = scripts.get(index).iterator();
        // hasNext 处轮询取消信号：脚本回放也遵守 checkAbort 协议
        Iterable<StreamChunk> abortable = () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                signal.checkAbort();
                return chunks.hasNext();
            }

            @Override
            public StreamChunk next() {
                return chunks.next();
            }
        };
        return StreamSupport.stream(abortable.spliterator(), false);
    }
}
