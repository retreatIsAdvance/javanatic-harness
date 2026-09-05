package io.javanatic.harness.agent;

import io.javanatic.harness.kernel.scope.Disposable;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * agent 服务：跟踪活跃 agent，携带 process-local initiator（JEP 506
 * ScopedValue 因果归因），create/resume 经注册的工厂。
 */
public final class AgentRegistry {

    /** 本服务的服务键。 */
    public static final ServiceKey<AgentRegistry> KEY = new ServiceKey<>("agents");

    private final ConcurrentHashMap<Id<Session>, Agent> agents = new ConcurrentHashMap<>();
    private final AtomicReference<AgentFactory> factory = new AtomicReference<>();

    private static final ScopedValue<Agent> INITIATOR = ScopedValue.newInstance();

    /**
     * 注册 agent 工厂（loop 插件在 apply 里调用，凭据挂自身 scope）。
     *
     * @param agentFactory 工厂
     * @throws IllegalStateException 已有工厂注册时
     * @return 注销凭据
     */
    public Disposable setFactory(AgentFactory agentFactory) {
        Objects.requireNonNull(agentFactory, "agentFactory");
        if (!factory.compareAndSet(null, agentFactory)) {
            throw new IllegalStateException("agent factory already registered");
        }
        return Disposable.of(() -> factory.compareAndSet(agentFactory, null));
    }

    /**
     * 创建新 agent（新会话）。会话 id 重复占用 fail loud。
     *
     * @param owner   拥有方 scope
     * @param options 创建输入
     * @return agent 句柄（dispose 完成后自动注销）
     */
    public AgentHandle create(Scope owner, CreateAgentOptions options) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(options, "options");
        requireIdle(options.sessionId());
        return register(requireFactory().create(owner, options));
    }

    /**
     * 恢复驱动既有会话。
     *
     * @param owner   拥有方 scope
     * @param options 恢复输入
     * @return agent 句柄
     */
    public AgentHandle resume(Scope owner, ResumeAgentOptions options) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(options, "options");
        requireIdle(options.sessionId());
        return register(requireFactory().resume(owner, options));
    }

    /** 按 id 查活跃 agent。 */
    public Agent get(Id<Session> id) {
        return agents.get(id);
    }

    /** 活跃 agent 快照。 */
    public List<Agent> list() {
        return List.copyOf(agents.values());
    }

    /** 当前 initiator（未绑定时 null）。 */
    public Agent currentInitiator() {
        return INITIATOR.isBound() ? INITIATOR.get() : null;
    }

    /**
     * 在 initiator 绑定内执行。绑定发生在调用线程及其后派生的虚拟线程
     * （创建时快照继承）；禁止池化 executor 提交（04 §10）。
     *
     * @param agent 归因 agent
     * @param op    操作
     * @param <T>   结果类型
     * @return 操作结果
     */
    public <T> T withInitiator(Agent agent, Supplier<T> op) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(op, "op");
        // 终版 JEP 506：Carrier 执行方法是 call/run（预览期的 get(Supplier) 已删）
        return ScopedValue.where(INITIATOR, agent).call(op::get);
    }

    private AgentFactory requireFactory() {
        AgentFactory current = factory.get();
        if (current == null) {
            throw new IllegalStateException("no agent factory registered (load the agent-loop plugin)");
        }
        return current;
    }

    private void requireIdle(Id<Session> id) {
        if (agents.containsKey(id)) {
            throw new IllegalStateException("agent already registered for session: " + id);
        }
    }

    private AgentHandle register(AgentHandle handle) {
        Agent existing = agents.putIfAbsent(handle.agent().id(), handle.agent());
        if (existing != null) {
            throw new IllegalStateException("agent already registered for session: " + handle.agent().id());
        }
        return new AgentHandle(handle.agent(), AgentHandle.once(() ->
            handle.dispose().thenRun(() -> agents.remove(handle.agent().id(), handle.agent()))));
    }
}
