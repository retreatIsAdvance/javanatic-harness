package io.javanatic.harness.lsp.jdtls;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import io.javanatic.harness.lsp.LspDiagnostic;

/**
 * 按文档 URI 的诊断候选批次缓存：每文档只留最新批次（携带 receiveSequence 截点与版本），
 * 条数 / 字节双帽防无界；超帽置溢出位（携带事件序列号——截点前的溢出毒化当次拉取，
 * Provider 拉取时映射 {@code LspException(PROTOCOL)}，不降级为空结果；
 * 截点后的溢出归下一次观察）。通知回调（读线程）与拉取线程并发触碰，全部并发容器。
 */
final class DiagnosticsCache {

    /** 每文档条目帽（最新批次的诊断条数）。 */
    static final int MAX_ENTRIES_PER_DOCUMENT = 512;

    /** 每文档字节帽（message/code/source 文本的近似预算）。 */
    static final int MAX_BYTES_PER_DOCUMENT = 64 * 1024;

    /** 一个已解析诊断批次：截点序号 + 可选 LS 版本 + 不可变条目。 */
    record Batch(long sequence, OptionalInt version, List<LspDiagnostic> items) {
        Batch {
            Objects.requireNonNull(version, "version");
            items = List.copyOf(items);
        }
    }

    /** 与 reader 发布锁互斥捕获，之后的新批次与溢出不能改变该快照。 */
    record Candidate(long sequence, Optional<Batch> batch, boolean overflowed) {
    }

    Candidate capture(URI uri, long sequence) {
        return new Candidate(sequence, snapshot(uri, sequence), overflowed(uri, sequence));
    }

    private final ConcurrentHashMap<URI, Batch> batches = new ConcurrentHashMap<>();
    /** 曾超帽文档 → 溢出事件序列号（与批次同样受截点纪律约束）。 */
    private final ConcurrentHashMap<URI, Long> overflowed = new ConcurrentHashMap<>();

    /** 记录一个批次（每文档只留最新）；超帽置溢出位并丢弃该批次。 */
    void publish(URI uri, long sequence, OptionalInt version, List<LspDiagnostic> items) {
        if (overflowed.containsKey(uri)) {
            return;
        }
        if (items.size() > MAX_ENTRIES_PER_DOCUMENT || bytes(items) > MAX_BYTES_PER_DOCUMENT) {
            overflowed.put(uri, sequence);
            batches.remove(uri);
            return;
        }
        batches.put(uri, new Batch(sequence, version, items));
    }

    /** 截点内的最新批次（无批次 / 批次晚于截点 / 已溢出 → 空）。 */
    Optional<Batch> snapshot(URI uri, long cutoff) {
        Batch batch = batches.get(uri);
        if (batch == null || batch.sequence() > cutoff) {
            return Optional.empty();
        }
        return Optional.of(batch);
    }

    /** 该文档是否在截点内曾超帽（截点前的溢出毒化当次拉取 = PROTOCOL；之后溢出归下一次观察）。 */
    boolean overflowed(URI uri, long cutoff) {
        Long sequence = overflowed.get(uri);
        return sequence != null && sequence <= cutoff;
    }

    /** 清空全部批次与溢出位（close 复位）。 */
    void clear() {
        batches.clear();
        overflowed.clear();
    }

    private static long bytes(List<LspDiagnostic> items) {
        long total = 0;
        for (LspDiagnostic item : items) {
            total += item.message().length()
                + item.code().map(String::length).orElse(0)
                + item.source().map(String::length).orElse(0);
        }
        return total;
    }
}
