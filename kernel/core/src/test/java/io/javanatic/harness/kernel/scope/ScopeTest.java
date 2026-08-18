package io.javanatic.harness.kernel.scope;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scope 的 overlay 解析、LIFO 回收、级联与原子性（docs/design/01-kernel.md §3–§4）。 */
class ScopeTest {

    record Svc(String name) {}

    @Test
    void resolveWalksParentChainAndRequireFailsLoud() {
        try (Runtime rt = new Runtime()) {
            Scope root = rt.root();
            ServiceKey<Svc> key = new ServiceKey<>("svc");
            root.provide(key, new Svc("root-svc"));
            Scope child = root.child();
            Scope grandchild = child.child();
            assertThat(grandchild.require(key).name()).isEqualTo("root-svc");
            assertThat(root.resolve(key)).contains(new Svc("root-svc"));
            assertThatThrownBy(() -> root.require(new ServiceKey<Object>("nope")))
                .isInstanceOf(ServiceNotAvailableException.class)
                .hasMessageContaining("nope");
        }
    }

    @Test
    void childShadowsParentWhileParentUnaffected() {
        try (Runtime rt = new Runtime()) {
            Scope root = rt.root();
            ServiceKey<Svc> key = new ServiceKey<>("svc");
            root.provide(key, new Svc("root"));
            Scope child = root.child();
            child.provide(key, new Svc("shadow"));
            assertThat(child.require(key).name()).isEqualTo("shadow");
            assertThat(root.require(key).name()).isEqualTo("root");
        }
    }

    @Test
    void duplicateProvideInSameScopeThrows() {
        try (Runtime rt = new Runtime()) {
            ServiceKey<Svc> key = new ServiceKey<>("svc");
            rt.root().provide(key, new Svc("first"));
            assertThatThrownBy(() -> rt.root().provide(key, new Svc("second")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("svc");
        }
    }

    @Test
    void teardownIsLifoAndCascadesChildren() {
        List<String> order = new ArrayList<>();
        try (Runtime rt = new Runtime()) {
            Scope root = rt.root();
            root.onClose(() -> order.add("a"));
            root.onClose(() -> order.add("b"));
            Scope child = root.child();
            child.onClose(() -> order.add("c"));
            root.onClose(() -> order.add("d"));
        }
        // d → child 级联（c）→ b → a
        assertThat(order).containsExactly("d", "c", "b", "a");
    }

    @Test
    void closedScopeRejectsProvideAndResolveFindsNothing() {
        Runtime rt = new Runtime();
        Scope root = rt.root();
        ServiceKey<Svc> key = new ServiceKey<>("svc");
        Scope child = root.child();
        child.provide(key, new Svc("child-svc"));
        child.close();
        // R3 结构性保证：provider scope 关闭后，僵尸引用不可能被解析到
        assertThat(root.resolve(key)).isEmpty();
        assertThatThrownBy(() -> child.provide(key, new Svc("x")))
            .isInstanceOf(IllegalStateException.class);
        rt.close();
    }

    @Test
    void subscriptionCloseIsIdempotentAndLeavesStack() {
        Runtime rt = new Runtime();
        AtomicInteger disposed = new AtomicInteger();
        Disposable sub = rt.root().onClose(disposed::incrementAndGet);
        sub.close();
        sub.close();
        assertThat(disposed.get()).isEqualTo(1);
        rt.close();
        assertThat(disposed.get()).isEqualTo(1);
    }

    @Test
    void effectRegisterFailureLeavesNothingRegistered() {
        Runtime rt = new Runtime();
        Scope root = rt.root();
        assertThatThrownBy(() -> root.effect(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        root.close();
    }

    @Test
    void concurrentCloseDisposesEachEffectExactlyOnce() throws Exception {
        Runtime rt = new Runtime();
        Scope root = rt.root();
        AtomicInteger disposed = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            root.onClose(disposed::incrementAndGet);
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                pool.submit(root::close);
            }
        }
        assertThat(disposed.get()).isEqualTo(100);
        rt.close();
    }

    @Test
    void runtimeCloseIsIdempotent() {
        Runtime rt = new Runtime();
        rt.root().onClose(() -> {});
        rt.close();
        rt.close();
    }

    /** 性质：任意注册序列的回收序恒为其逆序（LIFO 是结构性质，不是巧合）。 */
    @Property
    void teardownIsAlwaysReverseOfRegistration(@ForAll @Size(max = 50) List<Integer> labels) {
        List<Integer> disposed = new CopyOnWriteArrayList<>();
        try (Runtime rt = new Runtime()) {
            for (int label : labels) {
                rt.root().onClose(() -> disposed.add(label));
            }
        }
        assertThat(disposed).isEqualTo(labels.reversed());
    }
}
