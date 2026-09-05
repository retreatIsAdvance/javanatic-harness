package io.javanatic.harness.agentloop;

import io.javanatic.harness.agent.AgentCancelCause;
import io.javanatic.harness.agent.AgentFactory;
import io.javanatic.harness.agent.AgentHandle;
import io.javanatic.harness.agent.AgentOptions;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.agent.CancelOptions;
import io.javanatic.harness.agent.CreateAgentOptions;
import io.javanatic.harness.agent.ResumeAgentOptions;
import io.javanatic.harness.kernel.plugin.Plugin;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.LlmService;
import io.javanatic.harness.session.CreateOptions;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.systemprompt.SystemPromptService;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 注册 agent 工厂（id "agent-loop"）。全部治理依赖在 apply 期解析——
 * 组合缺任一（agents/llm/tools/loop-guard/system-prompt/session-store 未装载）
 * 即装载失败（R4：装配期 fail loud，不是首 turn 裸奔）。
 */
public final class AgentLoopPlugin implements Plugin {

    private final Clock clock;

    /** @param clock 事件时间来源（R1：测试注入冻结钟） */
    public AgentLoopPlugin(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "agent-loop";
    }

    @Override
    public Set<String> requires() {
        return Set.of("agents", "llm", "tools", "loop-guard", "system-prompt", "session-store");
    }

    @Override
    public void apply(Scope scope) {
        AgentRegistry registry = scope.require(AgentRegistry.KEY);
        scope.onClose(registry.setFactory(new Factory(registry,
            scope.require(LlmService.KEY),
            scope.require(ToolRegistry.KEY),
            scope.require(ToolExecutor.KEY),
            scope.require(SystemPromptService.KEY),
            scope.require(LoopGuard.KEY),
            clock)));
    }

    /** 工厂本体：不可变依赖束（record）+ create/resume。 */
    private record Factory(AgentRegistry registry, LlmService llm, ToolRegistry tools,
                           ToolExecutor executor, SystemPromptService prompts, LoopGuard guard,
                           Clock clock) implements AgentFactory {

        @Override
        public AgentHandle create(Scope owner, CreateAgentOptions options) {
            SessionStore store = owner.require(SessionStore.KEY);
            Session session = store.create(owner, options.sessionId(),
                new CreateOptions(options.seed(), options.header()));
            return mount(owner, session, options.options());
        }

        @Override
        public AgentHandle resume(Scope owner, ResumeAgentOptions options) {
            SessionStore store = owner.require(SessionStore.KEY);
            // get 对缺失会话本身 fail loud（NoSuchElementException）
            return mount(owner, store.get(options.sessionId()), options.options());
        }

        private AgentHandle mount(Scope owner, Session session, AgentOptions agentOptions) {
            Scope agentScope = owner.child();
            AgentLoopImpl agent = new AgentLoopImpl(agentScope, session, llm(), tools(), executor(),
                prompts(), guard(), registry(), clock(), agentOptions);
            // dispose 是显式触发的能力：工厂绝不构造期启动 teardown（创建即 cancel
            // 会清空 inbox，与首个 turn 竞态——04 §11 实现落定）
            return new AgentHandle(agent, AgentHandle.once(() -> {
                CompletableFuture<Void> done = new CompletableFuture<>();
                Thread.ofVirtual().name("jh-agent-dispose").start(() -> {
                    try {
                        agent.cancel(new AgentCancelCause.Disposed(), CancelOptions.DEFAULT);
                        agent.whenIdle().join();
                        agentScope.close();
                        done.complete(null);
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                    }
                });
                return done;
            }));
        }
    }
}
