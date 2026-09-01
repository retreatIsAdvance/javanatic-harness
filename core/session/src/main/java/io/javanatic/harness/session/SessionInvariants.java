package io.javanatic.harness.session;

import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.SurfaceEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.StepEnd;
import io.javanatic.harness.session.event.StepStart;

import java.util.List;

/**
 * 不变式复核（append/load 双侧结构性保证之外的独立检查，回放测试用）：
 * <ul>
 *   <li>信封连续：seq[i] == i</li>
 *   <li>turn/step 嵌套：turn 号 = TurnStart 计数；step 必在 turn 内且单调；配对闭合</li>
 *   <li>surface 重放：全部 surface 元数据经全新 SurfaceManager 再校验（provenance 三规则）</li>
 * </ul>
 */
public final class SessionInvariants {

    private SessionInvariants() {
    }

    /** @throws IllegalStateException 任一不变式被破坏 */
    public static void validate(List<LoggedEvent<? extends SessionEvent>> events) {
        SurfaceManager replay = new SurfaceManager();
        int openTurn = -1;
        int nextTurn = 0;
        int openStep = -1;
        int nextStep = 0;
        for (int i = 0; i < events.size(); i++) {
            LoggedEvent<? extends SessionEvent> entry = events.get(i);
            if (entry.seq() != i) {
                throw new IllegalStateException("seq " + entry.seq() + " at index " + i + " breaks contiguity");
            }
            if (entry.event() instanceof SurfaceEvent surfaceEvent) {
                replay.validateCandidate(entry.seq(), surfaceEvent);
                replay.commit(entry);
            }
            switch (entry.event()) {
                case TurnStart e -> {
                    expect(openTurn < 0, "turn/start inside open turn " + openTurn);
                    expect(e.turn() == nextTurn, "turn/start number " + e.turn() + " != expected " + nextTurn);
                    openTurn = e.turn();
                    nextTurn++;
                    nextStep = 0;
                }
                case TurnEnd e -> {
                    expect(openTurn == e.turn(), "turn/end " + e.turn() + " without matching open turn");
                    expect(openStep < 0, "turn/end inside open step " + openStep);
                    openTurn = -1;
                }
                case StepStart e -> {
                    expect(openTurn == e.turn(), "step/start " + e.step() + " outside open turn");
                    expect(openStep < 0, "step/start inside open step " + openStep);
                    expect(e.step() == nextStep, "step/start number " + e.step() + " != expected " + nextStep);
                    openStep = e.step();
                    nextStep++;
                }
                case StepEnd e -> {
                    expect(openStep == e.step() && openTurn == e.turn(), "step/end without matching open step");
                    openStep = -1;
                }
                default -> { /* 开放联合：其余事件不参与 turn/step 结构 */
                }
            }
        }
        expect(openTurn < 0, "log ends inside open turn " + openTurn);
        expect(openStep < 0, "log ends inside open step " + openStep);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
