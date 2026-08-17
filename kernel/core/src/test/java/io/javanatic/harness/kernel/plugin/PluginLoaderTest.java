package io.javanatic.harness.kernel.plugin;

import io.javanatic.harness.kernel.events.EventKey;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.kernel.scope.ServiceKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PluginLoader 的 fail-loud 三件套与逐插件原子回滚（R3）。 */
class PluginLoaderTest {

    record Svc(String name) {}

    static final EventKey<String> PING = EventKey.notify("plugin-test.ping", String.class);

    /** 测试插件：id + requires + 可失败的挂载体。 */
    static final class FakePlugin implements Plugin {
        final String id;
        final Set<String> requires;
        final Consumer<Scope> body;

        FakePlugin(String id, Consumer<Scope> body) {
            this(id, Set.of(), body);
        }

        FakePlugin(String id, Set<String> requires, Consumer<Scope> body) {
            this.id = id;
            this.requires = requires;
            this.body = body;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> requires() {
            return requires;
        }

        @Override
        public void apply(Scope scope) {
            body.accept(scope);
        }
    }

    @Test
    void indexDuplicateIdFailsLoud() {
        assertThatThrownBy(() -> PluginLoader.index(List.of(
            new FakePlugin("dup", s -> {}),
            new FakePlugin("dup", s -> {}))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dup");
    }

    @Test
    void discoverWithoutProvidersIsEmpty() {
        assertThat(new PluginLoader().discover()).isEmpty();
    }

    @Test
    void loadAllGivesEachPluginAChildScopeAndOrderHolds() {
        try (Runtime rt = new Runtime()) {
            ServiceKey<Svc> key = new ServiceKey<>("svc");
            List<String> applied = new ArrayList<>();
            new PluginLoader().loadAll(rt.root(), List.of(
                new FakePlugin("a", s -> {
                    applied.add("a");
                    s.provide(key, new Svc("from-a"));
                }),
                new FakePlugin("b", Set.of("a"), s -> {
                    applied.add("b");
                    s.require(key); // 父链可见 a 的提供
                })));
            assertThat(applied).containsExactly("a", "b");
            assertThat(rt.root().resolve(key)).contains(new Svc("from-a"));
        }
    }

    @Test
    void loadAllRejectsRequiresNotLoadedBefore() {
        try (Runtime rt = new Runtime()) {
            FakePlugin a = new FakePlugin("a", Set.of("b"), s -> {});
            FakePlugin b = new FakePlugin("b", s -> {});
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt.root(), List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not loaded before it");
        }
    }

    @Test
    void loadAllRejectsDuplicateIdInOrderedList() {
        try (Runtime rt = new Runtime()) {
            FakePlugin a = new FakePlugin("a", s -> {});
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt.root(), List.of(a, a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice");
        }
    }

    @Test
    void pluginFailureRollsBackItsWholeScope() {
        try (Runtime rt = new Runtime()) {
            ServiceKey<Svc> key1 = new ServiceKey<>("svc1");
            ServiceKey<Svc> key2 = new ServiceKey<>("svc2");
            AtomicInteger heard = new AtomicInteger();
            FakePlugin broken = new FakePlugin("broken", s -> {
                s.provide(key1, new Svc("one"));
                s.provide(key2, new Svc("two"));
                s.events().onGlobal(PING, (carrier, payload) -> heard.incrementAndGet());
                throw new IllegalStateException("apply boom");
            });
            assertThatThrownBy(() -> new PluginLoader().loadAll(rt.root(), List.of(broken)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plugin failed and rolled back: broken")
                .hasCauseInstanceOf(IllegalStateException.class);
            // R3：服务与订阅随子 scope 一并回滚
            assertThat(rt.root().resolve(key1)).isEmpty();
            assertThat(rt.root().resolve(key2)).isEmpty();
            rt.events().notifyAndWait(PING, rt.root(), this, "x").join();
            assertThat(heard.get()).isZero();
        }
    }

    @Test
    void topoSortOrdersDependenciesAndIsStable() {
        Plugin a = new FakePlugin("a", s -> {});
        Plugin b = new FakePlugin("b", Set.of("a"), s -> {});
        Plugin c = new FakePlugin("c", Set.of("b"), s -> {});
        List<Plugin> sorted = new PluginLoader().topoSort(List.of(c, b, a));
        assertThat(sorted.stream().map(Plugin::id)).containsExactly("a", "b", "c");
    }

    @Test
    void topoSortCycleFailsLoud() {
        Plugin a = new FakePlugin("a", Set.of("b"), s -> {});
        Plugin b = new FakePlugin("b", Set.of("a"), s -> {});
        assertThatThrownBy(() -> new PluginLoader().topoSort(List.of(a, b)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void topoSortUnknownDependencyFailsLoud() {
        Plugin a = new FakePlugin("a", Set.of("ghost"), s -> {});
        assertThatThrownBy(() -> new PluginLoader().topoSort(List.of(a)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ghost");
    }
}
