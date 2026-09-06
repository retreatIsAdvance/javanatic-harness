package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 取消钩子:cancel 同步触发、已取消后注册立即执行、never() 无操作、动作异常不阻断。 */
class AbortControllerTest {

    @Test
    void cancelTriggersRegisteredActionSynchronously() {
        AbortController controller = new AbortController();
        List<String> fired = new CopyOnWriteArrayList<>();
        AbortSignal signal = controller.signal();
        signal.onCancel(() -> fired.add("kill"));
        assertThat(fired).isEmpty();

        controller.cancel(new AgentCancelCause.User());
        assertThat(fired).containsExactly("kill");
        assertThatThrownBy(signal::checkAbort).isInstanceOf(AbortedException.class);
    }

    @Test
    void lateRegistrationRunsImmediately() {
        AbortController controller = new AbortController();
        controller.cancel(new AgentCancelCause.Disposed());
        List<String> fired = new CopyOnWriteArrayList<>();
        controller.signal().onCancel(() -> fired.add("late"));
        assertThat(fired).containsExactly("late");
    }

    @Test
    void failingActionDoesNotBlockOthersOrCancellation() {
        AbortController controller = new AbortController();
        List<String> fired = new CopyOnWriteArrayList<>();
        controller.signal().onCancel(() -> {
            throw new IllegalStateException("boom");
        });
        controller.signal().onCancel(() -> fired.add("second"));
        controller.cancel(new AgentCancelCause.User());
        assertThat(fired).containsExactly("second");
        assertThat(controller.isAborted()).isTrue();
    }

    @Test
    void neverSignalIgnoresCancelHook() {
        AbortSignal never = AbortSignal.never();
        never.onCancel(() -> {
            throw new IllegalStateException("不应被调用");
        });
        never.checkAbort();
    }

    @Test
    void firstCauseWins() {
        AbortController controller = new AbortController();
        controller.cancel(new AgentCancelCause.User());
        controller.cancel(new AgentCancelCause.Disposed());
        assertThat(controller.cause()).isInstanceOf(AgentCancelCause.User.class);
    }
}
