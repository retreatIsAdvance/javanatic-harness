package io.javanatic.harness.agent;

import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.UserMessage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Registry：工厂注册/注销、create/resume 登记、dispose 注销、initiator 绑定与虚拟线程继承。 */
class AgentRegistryTest {

    /** 最小可注册 agent：行为与本测试断言的字段一一对应。 */
    private static final class FakeAgent implements Agent {
        private final Id<Session> id;

        private FakeAgent(Id<Session> id) {
            this.id = id;
        }

        @Override public Id<Session> id() {
            return id;
        }

        @Override public AgentOptions options() {
            return new AgentOptions("replay", "test");
        }

        @Override public Session session() {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override public Inbox inbox() {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override public AgentStatus status() {
            return AgentStatus.IDLE;
        }

        @Override public Scope scope() {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override public void send(UserMessage message, InboxTarget target, boolean wakeup) {
            // no-op
        }

        @Override public void cancel(AgentCancelCause cause, CancelOptions options) {
            // no-op
        }

        @Override public CompletableFuture<Void> whenIdle() {
            return CompletableFuture.completedFuture(null);
        }

        @Override public <T> CompletableFuture<T> runMaintenance(Supplier<T> task) {
            return CompletableFuture.completedFuture(task.get());
        }
    }

    /** 记录 create 调用并回放句柄的工厂。 */
    private static final class RecordingFactory implements AgentFactory {
        AgentHandle next;

        @Override public AgentHandle create(Scope owner, CreateAgentOptions options) {
            return next;
        }

        @Override public AgentHandle resume(Scope owner, ResumeAgentOptions options) {
            return next;
        }
    }

    private static AgentHandle handle(Id<Session> id) {
        return new AgentHandle(new FakeAgent(id), AgentHandle.once(() -> CompletableFuture.completedFuture(null)));
    }

    private static AgentOptions options() {
        return new AgentOptions("replay", "test");
    }

    @Test
    void createWithoutFactoryFailsLoud() {
        try (Runtime rt = new Runtime()) {
            AgentRegistry registry = new AgentRegistry();
            assertThatThrownBy(() -> registry.create(rt.root(),
                    CreateAgentOptions.of(Session.newId("s"), options())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent factory");
        }
    }

    @Test
    void setFactoryTwiceFailsLoud() {
        AgentRegistry registry = new AgentRegistry();
        RecordingFactory factory = new RecordingFactory();
        registry.setFactory(factory);
        assertThatThrownBy(() -> registry.setFactory(new RecordingFactory()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void createRegistersAndDisposeDeregisters() {
        try (Runtime rt = new Runtime()) {
            AgentRegistry registry = new AgentRegistry();
            RecordingFactory factory = new RecordingFactory();
            registry.setFactory(factory);
            Id<Session> id = Session.newId("s1");
            CompletableFuture<Void> dispose = new CompletableFuture<>();
            factory.next = new AgentHandle(new FakeAgent(id), () -> dispose);

            AgentHandle handle = registry.create(rt.root(), CreateAgentOptions.of(id, options()));
            assertThat(registry.get(id)).isSameAs(handle.agent());
            assertThat(registry.list()).hasSize(1);

            // 工厂 dispose 链完成后注销
            dispose.complete(null);
            assertThat(handle.dispose().isDone()).isTrue();
            assertThat(registry.get(id)).isNull();
            assertThat(registry.list()).isEmpty();
        }
    }

    @Test
    void duplicateSessionFailsLoudBothSides() {
        try (Runtime rt = new Runtime()) {
            AgentRegistry registry = new AgentRegistry();
            RecordingFactory factory = new RecordingFactory();
            registry.setFactory(factory);
            Id<Session> id = Session.newId("s2");
            factory.next = handle(id);
            registry.create(rt.root(), CreateAgentOptions.of(id, options()));

            // 预检查路径
            assertThatThrownBy(() -> registry.create(rt.root(), CreateAgentOptions.of(id, options())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
        }
    }

    @Test
    void resumeDelegatesToFactoryAndRegisters() {
        try (Runtime rt = new Runtime()) {
            AgentRegistry registry = new AgentRegistry();
            RecordingFactory factory = new RecordingFactory();
            registry.setFactory(factory);
            Id<Session> id = Session.newId("s3");
            factory.next = handle(id);

            AgentHandle handle = registry.resume(rt.root(), new ResumeAgentOptions(id, options()));
            assertThat(handle.agent().id()).isEqualTo(id);
            assertThat(registry.get(id)).isSameAs(handle.agent());
        }
    }

    @Test
    void initiatorUnboundOutsideAndBoundInside() {
        AgentRegistry registry = new AgentRegistry();
        assertThat(registry.currentInitiator()).isNull();

        Id<Session> id = Session.newId("s4");
        FakeAgent agent = new FakeAgent(id);
        String seen = registry.withInitiator(agent, () -> registry.currentInitiator() == agent ? "bound" : "missing");
        assertThat(seen).isEqualTo("bound");
        assertThat(registry.currentInitiator()).isNull();
    }

    @Test
    void virtualThreadSpawnedInBindingDoesNotInheritInitiator() throws Exception {
        // JDK 25 终版 JEP 506：跨线程共享仅限 StructuredTaskScope.fork（preview，项目禁用）；
        // 普通 Thread.ofVirtual().start() 不继承绑定。initiator 归因只覆盖 driver
        // 线程的同步执行段（04 §10 实现落定）——本测试把语言边界钉成契约。
        AgentRegistry registry = new AgentRegistry();
        FakeAgent agent = new FakeAgent(Session.newId("s5"));
        AtomicReference<String> seen = new AtomicReference<>("unset");
        CountDownLatch done = new CountDownLatch(1);
        registry.withInitiator(agent, () -> {
            Thread.ofVirtual().start(() -> {
                seen.set(registry.currentInitiator() == agent ? "inherited" : "lost");
                done.countDown();
            });
            return null;
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo("lost");
    }
}
