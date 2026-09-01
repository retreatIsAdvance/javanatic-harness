package io.javanatic.harness.session;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.SurfaceEvent;
import io.javanatic.harness.session.event.SurfaceOp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 有序 surface 的增量维护者：surface 节点 seq 列表 + 重写代数。
 * validateCandidate 只读校验（失败时零变更）；commit 才变更。
 * 包私有：投影细节是 Session 的实现，外部只见 deriveMessages 结果。
 */
final class SurfaceManager {

    private final List<Long> nodes = new ArrayList<>();
    private long replaceGeneration;

    /** 校验候选 surface 事件能否提交（不改任何状态；provenance 在变更前检查）。 */
    void validateCandidate(long seq, SurfaceEvent event) {
        switch (event.surfaceOp()) {
            case SurfaceOp.Append a -> {
                if (event.sourceEventSeqs() == null) return; // 缺省 = 不记录来源
                assertProvenance(event, event.sourceEventSeqs(), new long[0], seq);
            }
            case SurfaceOp.Replace r -> {
                int startIdx = nodes.indexOf(r.start());
                int endIdx = nodes.indexOf(r.end());
                if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) {
                    throw new IllegalArgumentException(
                        "Replace range invalid: [" + r.start() + "," + r.end() + "] not a current surface range");
                }
                long[] shadowed = new long[endIdx - startIdx + 1];
                for (int i = 0; i < shadowed.length; i++) {
                    shadowed[i] = nodes.get(startIdx + i);
                }
                List<Long> sources = event.sourceEventSeqs();
                if (sources == null) {
                    throw new IllegalArgumentException("Replace requires sourceEventSeqs covering the shadowed range");
                }
                assertProvenance(event, sources, shadowed, seq);
            }
        }
    }

    /**
     * provenance 三规则：source ⊇ 全部被 shadow 的 seq；所有 source &lt; 当前 seq
     * （不许引用未来）；无重复。空列表仅 assistant/message 合法（已知空流）。
     */
    private static void assertProvenance(SurfaceEvent event, List<Long> sources, long[] shadowed, long seq) {
        if (sources.isEmpty() && !(event instanceof AssistantMessageEvent)) {
            throw new IllegalArgumentException(
                event.getClass().getSimpleName() + " sourceEventSeqs must be non-empty when present");
        }
        Set<Long> seen = new HashSet<>();
        for (long source : sources) {
            if (!seen.add(source)) {
                throw new IllegalArgumentException("sourceEventSeqs must not contain duplicates: " + source);
            }
            if (source >= seq) {
                throw new IllegalArgumentException(
                    "sourceEventSeqs must reference earlier events: " + source + " >= current seq " + seq);
            }
        }
        for (long shadow : shadowed) {
            if (!seen.contains(shadow)) {
                throw new IllegalArgumentException(
                    "Replace must cite every shadowed surface node; missing " + shadow);
            }
        }
    }

    /** 提交（append 尾追加；replace 换段并递增重写代数）。调用前必须已 validateCandidate。 */
    void commit(LoggedEvent<? extends SessionEvent> entry) {
        SurfaceEvent event = (SurfaceEvent) entry.event();
        switch (event.surfaceOp()) {
            case SurfaceOp.Append a -> nodes.add(entry.seq());
            case SurfaceOp.Replace r -> {
                int startIdx = nodes.indexOf(r.start());
                int endIdx = nodes.indexOf(r.end());
                nodes.subList(startIdx, endIdx + 1).clear();
                nodes.add(startIdx, entry.seq());
                replaceGeneration++;
            }
        }
    }

    /** 当前 surface 节点数（投影缓存推进用）。 */
    int nodeCount() {
        return nodes.size();
    }

    /** 第 index 个 surface 节点的 seq（即日志下标——seq 与下标恒等）。 */
    long nodeAt(int index) {
        return nodes.get(index);
    }

    /** 重写代数：每次已提交的 replace 递增，投影缓存据此整体失效。 */
    long replaceGeneration() {
        return replaceGeneration;
    }
}
