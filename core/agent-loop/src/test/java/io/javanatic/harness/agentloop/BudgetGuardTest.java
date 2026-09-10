package io.javanatic.harness.agentloop;

import io.javanatic.harness.kernel.config.ConfigService;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.AssistantMessageEvent;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.message.AssistantMessage;
import io.javanatic.harness.session.message.MessageSource;
import io.javanatic.harness.session.message.TextBlock;
import io.javanatic.harness.session.message.TokenUsage;
import io.javanatic.harness.session.event.SurfaceOp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** budget 档:累计输出 tokens 超限优雅拒;零=不限;PRODUCTION 断言。 */
class BudgetGuardTest {

    private LoopGuard guardWith(long budget) {
        return new LoopGuardPlugin(new LoopGuard.Limits(10, 10, budget)).guardForTest();
    }

    private static Session sessionWithOutput(long... outputTokens) {
        Session session = Session.create(Session.newId("bg"), null, null);
        session.append(new TurnStart(1, 1));
        for (int i = 0; i < outputTokens.length; i++) {
            session.append(new AssistantMessageEvent(2 + i, 1, i,
                new AssistantMessage(new MessageSource.Model("replay", "m"),
                    List.of(new TextBlock("答"))),
                new TokenUsage(100, outputTokens[i], 0),
                new SurfaceOp.Append(), null));
        }
        return session;
    }

    @Test
    void cumulativeOutputOverBudgetRejects() {
        Session session = sessionWithOutput(30, 30);
        assertThatThrownBy(() -> guardWith(50).checkBudget(session, 1, 2))
            .isInstanceOf(GuardRejectException.class)
            .hasMessageContaining("token budget exceeded: 60 > 50");
    }

    @Test
    void zeroBudgetMeansUnlimited() {
        Session session = sessionWithOutput(9999, 9999);
        assertThatCode(() -> guardWith(0).checkBudget(session, 1, 2)).doesNotThrowAnyException();
    }

    @Test
    void withinBudgetPasses() {
        Session session = sessionWithOutput(20, 20);
        assertThatCode(() -> guardWith(50).checkBudget(session, 1, 2)).doesNotThrowAnyException();
    }

    @Test
    void twoArgFactoryKeepsZeroBudget() {
        LoopGuard.Limits limits = new LoopGuard.Limits(5, 5);
        assertThatCode(() -> guardWith(limits.maxBudgetTokens())
            .checkBudget(sessionWithOutput(9999), 1, 0)).doesNotThrowAnyException();
    }

    @Test
    void configPathResolvesBudget() throws Exception {
        try (Runtime rt = new Runtime()) {
            rt.root().provide(ConfigService.KEY, id -> id.equals("loop-guard")
                ? Map.of("maxBudgetTokens", 100) : Map.of());
            new PluginLoader().loadAll(rt, List.of(new LoopGuardPlugin()));
            LoopGuard guard = rt.root().require(LoopGuard.KEY);
            assertThatThrownBy(() -> guard.checkBudget(sessionWithOutput(60, 60), 1, 2))
                .hasMessageContaining("token budget exceeded: 120 > 100");
        }
    }
}
