package io.javanatic.harness.workbench.projection;

import java.util.List;
import java.util.Objects;

/**
 * tools 视图:一个 (turn, step) 内的工具调用配对。
 *
 * <p>配对键是 callId——ToolResultBlock.toolUseId 与 ToolCallEvent.callId
 * (R2 审计对的同一把键):同 step 内每把 callId 的首个 result 配首个 call,
 * 与日志相邻性无关(Session 并发契约:跨执行者事件交错序任意)。
 *
 * @param pairs   按调用序的配对结果;resultSeq=null 的 Pair = 调用无结果(「结果未知」)
 * @param orphans 无对应调用的孤儿结果(按结果序;审计完整性异常,投影如实呈现)
 */
public record ToolStep(int turn, int step, List<Pair> pairs, List<Orphan> orphans) {

    /** 构造时冻结列表(不可变性由构造时归一实现)。 */
    public ToolStep {
        pairs = List.copyOf(pairs);
        orphans = List.copyOf(orphans);
    }

    /**
     * 一次调用与其结果(若有)的配对。
     *
     * @param callSeq        tool/call 的日志 seq
     * @param name           工具名
     * @param arguments      原始 arguments JSON(未解析)
     * @param resultSeq      tool/result 的日志 seq;null = 调用无结果(结果未知)
     * @param isError        结果是否错误;null = 无结果
     * @param concludesTurn  结果是否终结本轮;无结果时为 false
     * @param durationMillis 结果耗时(result.time - call.time);无结果时为 -1
     */
    public record Pair(long callSeq, String name, String arguments,
                       Long resultSeq, Boolean isError, boolean concludesTurn,
                       long durationMillis) {

        /** @throws NullPointerException name/arguments 为 null 时 */
        public Pair {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /** 无对应调用的孤儿结果(审计完整性异常:结果存在而调用缺失)。 */
    public record Orphan(long resultSeq, boolean isError, boolean concludesTurn) {
    }
}
