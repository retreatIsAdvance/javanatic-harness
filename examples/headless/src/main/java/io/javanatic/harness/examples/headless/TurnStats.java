package io.javanatic.harness.examples.headless;

import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.StepStart;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.TokenUsage;

import java.util.List;
import java.util.Locale;

/**
 * 轮末资源统计（it20 ⑤）：steps / tokens in-out / 耗时——REPL 轮末行与
 * one-shot 成功路径 stderr 行经 {@link #line()} 同形。数据源全在事件流：
 * step/start 计数、assistant/message 的 usage 求和（适配器未报告按 0，与
 * loop-guard 预算计量同源）、turn/start→turn/end 事件时间差——不读环境时钟。
 */
record TurnStats(int turn, int steps, long inputTokens, long outputTokens, long elapsedMillis) {

    String line() {
        return "stats: turn=" + turn + " steps=" + steps
            + " tokens_in=" + inputTokens + " tokens_out=" + outputTokens
            + " elapsed=" + String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1000.0);
    }

    /** 事件切片折叠（one-shot 出口用）：以末个 turn/end 收口。 */
    static TurnStats of(List<LoggedEvent<? extends SessionEvent>> live) {
        Accumulator acc = new Accumulator();
        TurnEnd end = null;
        for (LoggedEvent<? extends SessionEvent> entry : live) {
            acc.on(entry.event());
            if (entry.event() instanceof TurnEnd turnEnd) {
                end = turnEnd;
            }
        }
        if (end == null) {
            throw new IllegalStateException("无 turn/end 可统计");
        }
        return acc.finish(end);
    }

    /** 事件流增量累计（REPL 渲染线程用；与 {@link #of} 同一口径）。 */
    static final class Accumulator {

        private int steps;
        private long tokensIn;
        private long tokensOut;
        private long startMillis = Long.MIN_VALUE;

        void on(SessionEvent event) {
            switch (event) {
                case TurnStart start -> {
                    steps = 0;
                    tokensIn = 0;
                    tokensOut = 0;
                    startMillis = start.time();
                }
                case StepStart ignored -> steps++;
                case AssistantMessageEvent message -> {
                    TokenUsage usage = message.usage();
                    if (usage != null) {
                        tokensIn += usage.inputTokens();
                        tokensOut += usage.outputTokens();
                    }
                }
                default -> { }
            }
        }

        TurnStats finish(TurnEnd end) {
            long elapsed = startMillis == Long.MIN_VALUE ? 0 : Math.max(0, end.time() - startMillis);
            return new TurnStats(end.turn(), steps, tokensIn, tokensOut, elapsed);
        }
    }
}
