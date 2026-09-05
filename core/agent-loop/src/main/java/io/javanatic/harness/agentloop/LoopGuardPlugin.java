package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;

import java.util.Objects;

/**
 * 提供计数上限版 LoopGuard（id "loop-guard"，limits 是组合期选择——
 * 构造注入，无隐式默认）。budget 档（token 计量）随 deepseek 切片。
 */
public final class LoopGuardPlugin implements Plugin {

    private final LoopGuard guard;

    /** @param limits 上限（显式组合选择） */
    public LoopGuardPlugin(LoopGuard.Limits limits) {
        Objects.requireNonNull(limits, "limits");
        this.guard = new ConfigurableGuard(limits);
    }

    @Override
    public String id() {
        return "loop-guard";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(LoopGuard.KEY, guard);
    }

    private static final class ConfigurableGuard implements LoopGuard {
        private final Limits limits;

        ConfigurableGuard(Limits limits) {
            this.limits = limits;
        }

        @Override
        public void checkBudget(Session session, int turn, int step) {
            if (turn > limits.maxTurns()) {
                throw new GuardRejectException("max turns exceeded: " + turn + " > " + limits.maxTurns());
            }
            if (step >= limits.maxStepsPerTurn()) {
                throw new GuardRejectException("max steps per turn exceeded: " + step + " >= " + limits.maxStepsPerTurn());
            }
        }

        @Override
        public Limits limits() {
            return limits;
        }
    }
}
