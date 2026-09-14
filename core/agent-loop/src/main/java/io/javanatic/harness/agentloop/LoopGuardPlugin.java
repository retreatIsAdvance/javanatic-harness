package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.config.ConfigValues;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.message.TokenUsage;

import java.util.Map;
import java.util.Objects;

/**
 * 提供计数上限版 LoopGuard（id "loop-guard"）。两条装配路径等价：
 * 显式构造器（程序化组合）或无参 + ConfigService 行配置（数据组合，07 §4）——
 * config 只携带组合层给出的值，未给的用插件侧文档化默认（50/40）。
 * budget 档（token 计量）：maxBudgetTokens 非零时按事件流累计 output token 判超限（0=不限）。
 */
public final class LoopGuardPlugin implements Plugin {

    /** 数据组合路径的文档化默认（组合层未给 config 时生效）。 */
    public static final long DEFAULT_MAX_TURNS = 50;
    public static final long DEFAULT_MAX_STEPS_PER_TURN = 40;

    private final LoopGuard explicit;

    /** 测试访问器。 */
    LoopGuard guardForTest() {
        return explicit;
    }

    /** 数据组合路径：limits 从行配置解析（maxTurns/maxStepsPerTurn）。 */
    public LoopGuardPlugin() {
        this.explicit = null;
    }

    /** @param limits 上限（程序化组合的显式选择） */
    public LoopGuardPlugin(LoopGuard.Limits limits) {
        Objects.requireNonNull(limits, "limits");
        this.explicit = new ConfigurableGuard(limits);
    }

    @Override
    public String id() {
        return "loop-guard";
    }

    @Override
    public void apply(Scope scope) {
        scope.provide(LoopGuard.KEY, explicit != null ? explicit : fromConfig(scope));
    }

    private LoopGuard fromConfig(Scope scope) {
        Map<String, Object> config = scope.require(ConfigService.KEY).configFor(id());
        return new ConfigurableGuard(new LoopGuard.Limits(
            (int) ConfigValues.longValue(config, id(), "maxTurns", DEFAULT_MAX_TURNS),
            (int) ConfigValues.longValue(config, id(), "maxStepsPerTurn", DEFAULT_MAX_STEPS_PER_TURN),
            ConfigValues.longValue(config, id(), "maxBudgetTokens", 0)));
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
            if (limits.maxBudgetTokens() > 0) {
                long spent = session.events().stream()
                    .map(entry -> entry.event())
                    .filter(AssistantMessageEvent.class::isInstance)
                    .map(event -> ((AssistantMessageEvent) event).usage())
                    .filter(usage -> usage != null)
                    .mapToLong(TokenUsage::outputTokens).sum();
                if (spent > limits.maxBudgetTokens()) {
                    throw new GuardRejectException("token budget exceeded: " + spent + " > "
                        + limits.maxBudgetTokens() + " (cumulative output tokens)");
                }
            }
        }

        @Override
        public Limits limits() {
            return limits;
        }
    }
}
