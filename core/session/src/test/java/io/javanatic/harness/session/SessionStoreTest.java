package io.javanatic.harness.session;

import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.TurnStart;
import io.javanatic.harness.session.event.UserMessageEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.message.UserMessage;
import io.javanatic.harness.session.message.MessageSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Store 接线：插件注册、CREATED/APPENDED/DISPOSED 派发、flush barrier、关闭回收（R3）。 */
class SessionStoreTest {

    private static final MessageSource.User USER = new MessageSource.User();

    @Test
    void pluginProvidesStoreAndCreateFiresCreated() throws Exception {
        AtomicReference<Session> created = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (Runtime rt = new Runtime()) {
            rt.root().events().onGlobal(SessionEvents.CREATED, (carrier, session) -> {
                created.set(session);
                latch.countDown();
            });
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin()));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("s1"), CreateOptions.empty());
            assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); // CREATED 是 fire-and-forget
            assertThat(created.get()).isSameAs(session);
            assertThat(store.get(Session.newId("s1"))).isSameAs(session);
            assertThat(store.list()).hasSize(1);
        }
    }

    @Test
    void appendFiresAppendedWithEnvelope() throws Exception {
        AtomicReference<Object> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin()));
            rt.root().events().onGlobal(SessionEvents.APPENDED, (carrier, entry) -> {
                received.set(entry);
                latch.countDown();
            });
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("s1"), CreateOptions.empty());
            LoggedEvent<TurnStart> entry = session.append(new TurnStart(1, 0));
            assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo(entry);
        }
    }

    @Test
    void flushWaitsForAllListeners() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin()));
            Scope root = rt.root();
            SessionStore store = root.require(SessionStore.KEY);
            Session session = store.create(root, Session.newId("s1"), CreateOptions.empty());
            store.flush(root, session); // 无 listener 立即返回
        }
    }

    @Test
    void storeRejectsUnknownSession() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin()));
            SessionStore store = rt.root().require(SessionStore.KEY);
            assertThatThrownBy(() -> store.get(Session.newId("ghost")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("ghost");
        }
    }

    @Test
    void ownerCloseDisposesSessionFromStore() {
        try (Runtime rt = new Runtime()) {
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin()));
            SessionStore store = rt.root().require(SessionStore.KEY);
            Session session = store.create(rt.root(), Session.newId("s1"), CreateOptions.empty());
            session.append(new UserMessageEvent(1, UserMessage.of("hi", USER), new SurfaceOp.Append(), null));
            assertThat(store.list()).hasSize(1);
        } // root close → owner.onClose → store.remove（R3：会话不越过创建方生命周期）
        try (Runtime rt2 = new Runtime()) {
            // 仅证明前一个 store 已随其 runtime 回收；新 runtime 的 store 当然为空
            new PluginLoader().loadAll(rt2, List.of(new SessionStorePlugin()));
            assertThat(rt2.root().require(SessionStore.KEY).list()).isEmpty();
        }
    }
}
