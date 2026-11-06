package io.javanatic.harness.delegation.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javanatic.harness.agent.AgentPlugin;
import io.javanatic.harness.agentloop.AgentLoopPlugin;
import io.javanatic.harness.agentloop.LoopGuard;
import io.javanatic.harness.agentloop.LoopGuardPlugin;
import io.javanatic.harness.agent.AgentRegistry;
import io.javanatic.harness.tools.ApprovalAutoPlugin;
import io.javanatic.harness.delegation.Delegation;
import io.javanatic.harness.delegation.DelegationException;
import io.javanatic.harness.delegation.DelegationRequest;
import io.javanatic.harness.delegation.DelegationService;
import io.javanatic.harness.delegation.DelegationSnapshot;
import io.javanatic.harness.delegation.DelegationState;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.FinishReason;
import io.javanatic.harness.llm.LlmPlugin;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.llm.replay.ReplayPlugin;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.persistence.SessionPersistence;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.systemprompt.SystemPromptPlugin;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.interaction.ask.AskUserPlugin;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 停 3 排序条件兑现：集成先行（rig + replay 真子代，先于任何消费面代码）。
 * rig = 显式插件清单（PlanModePluginTest 先例）；持久化用桩（save 无操作——
 * 持久化机制 it30 探针已证；本 rig 靶向生命周期/准入/取消/恰一次）。
 * 四路映射中的两路（Succeeded / NeedsParentInput→resume→Succeeded）由剧本
 * 驱动端到端取证；Error/Aborted 路径纯函数层已钉（TurnClassifierTest）。
 */
class LocalDelegationIntegrationTest {

    private Runtime rt;
    private Session parent;
    private LocalDelegationService service;

    @BeforeEach
    void boot() {
        rt = new Runtime();
    }

    @AfterEach
    void shutdown() {
        if (rt != null) {
            rt.close();
        }
    }

    @Test
    void scriptedChildSucceedsAndLedgerHasExactlyOneTerminal() throws Exception {
        bootRig(List.of(List.of(new StreamChunk.Delta("子代完成：三个模块的检索结果如下"),
                new StreamChunk.Finish(FinishReason.STOP))));
        bind(1);
        DelegationSnapshot started = service.start(request("并行检索三个模块并汇总"));
        DelegationSnapshot terminal = awaitSettled(started.id());
        assertThat(terminal.state()).isInstanceOf(DelegationState.Succeeded.class);
        assertThat(((DelegationState.Succeeded) terminal.state()).resultSummary()).contains("检索结果");
        long terminalCount = delegationEvents().stream()
                .map(DelegationSnapshotEvent.class::cast)
                .filter(e -> e.snapshot().state().terminal()).count();
        assertThat(terminalCount).as("终局事件恰一次").isEqualTo(1);
        assertThat(service.list()).hasSize(1);
        // 预算贯通回放（R1）：父账 fold 的授予值 == 运行期请求值——零运行期隐藏数
        long replayedBudget = delegationEvents().stream()
                .map(DelegationSnapshotEvent.class::cast)
                .mapToLong(e -> e.snapshot().budgetTokens()).max().orElse(-1);
        assertThat(replayedBudget).as("回放聚合 == 运行期值").isEqualTo(5000);
    }

    @Test
    void askLoopPausesResumesAndSucceeds() throws Exception {
        bootRig(List.of(
                List.of(new StreamChunk.DeltaToolUse(CallId.of("q1"), "ask_user",
                                "{\"question\":\"用哪个分支？\"}"),
                        new StreamChunk.Finish(FinishReason.TOOL_USE)),
                List.of(new StreamChunk.Delta("收到答复：按主分支完成"),
                        new StreamChunk.Finish(FinishReason.STOP))));
        bind(1);
        DelegationSnapshot started = service.start(request("整理分支策略"));
        DelegationSnapshot paused = awaitSettled(started.id());
        assertThat(paused.state()).isInstanceOf(DelegationState.NeedsParentInput.class);
        assertThat(((DelegationState.NeedsParentInput) paused.state()).question()).isEqualTo("用哪个分支？");
        DelegationSnapshot done = service.resume(started.id(), "用主分支");
        assertThat(done.state()).isInstanceOf(DelegationState.Running.class);
        assertThat(awaitTerminal(started.id()).state()).isInstanceOf(DelegationState.Succeeded.class);
    }

    @Test
    void pausedChildHoldsSlotAndGateRejectsSecondStart() throws Exception {
        bootRig(List.of(
                List.of(new StreamChunk.DeltaToolUse(CallId.of("q1"), "ask_user",
                                "{\"question\":\"确认范围？\"}"),
                        new StreamChunk.Finish(FinishReason.TOOL_USE)),
                List.of(new StreamChunk.Delta("ok"), new StreamChunk.Finish(FinishReason.STOP))));
        bind(1);
        DelegationSnapshot first = service.start(request("第一件任务"));
        awaitSettled(first.id());
        assertThatThrownBy(() -> service.start(request("第二件任务")))
                .isInstanceOf(DelegationException.class)
                .hasMessageContaining("maxActive");
    }

    @Test
    void cancelPausedChildFinalizesCancelledAndIsIdempotent() throws Exception {
        bootRig(List.of(
                List.of(new StreamChunk.DeltaToolUse(CallId.of("q1"), "ask_user",
                                "{\"question\":\"还继续吗？\"}"),
                        new StreamChunk.Finish(FinishReason.TOOL_USE)),
                List.of(new StreamChunk.Delta("ok"), new StreamChunk.Finish(FinishReason.STOP))));
        bind(1);
        DelegationSnapshot started = service.start(request("可取消任务"));
        awaitSettled(started.id());
        assertThat(service.cancel(started.id())).isTrue();
        assertThat(service.snapshot(started.id()).state()).isInstanceOf(DelegationState.Cancelled.class);
        assertThat(service.cancel(started.id())).isFalse();
    }

    private void bootRig(List<List<StreamChunk>> scripts) {
        // 权威 rig 面（AgentLoopTest 同款）+ ask-user（问句路径的 ask_user 工具）
        new PluginLoader().loadAll(rt, List.of(
                new SessionStorePlugin(), new AgentPlugin(),
                new LoopGuardPlugin(new LoopGuard.Limits(20, 10, 0)),
                new SystemPromptPlugin(), new LlmPlugin(), new ReplayPlugin(scripts),
                new ApprovalAutoPlugin(), new ToolsPlugin(),
                new AgentLoopPlugin(java.time.Clock.systemUTC(), System.getProperty("user.dir")),
                new AskUserPlugin()));
        service = new LocalDelegationService(rt.root(),
                rt.root().require(AgentRegistry.KEY), stubPersistence(), "replay", "m", 1);
        rt.root().provide(DelegationService.KEY, service);
    }

    private void bind(int unused) {
        parent = Session.create(Session.newId("del-parent-" + System.nanoTime()), null, null);
        service.bindSession(parent);
    }

    private static DelegationRequest request(String task) {
        return new DelegationRequest(task, Path.of(System.getProperty("user.dir")), 5000);
    }

    private DelegationSnapshot awaitSettled(Id<Delegation> id)
            throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            DelegationSnapshot snapshot = service.snapshot(id);
            if (snapshot.state().terminal() || snapshot.state().paused()) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("delegation did not settle: " + service.snapshot(id));
    }

    private DelegationSnapshot awaitTerminal(Id<Delegation> id) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            DelegationSnapshot snapshot = service.snapshot(id);
            if (snapshot.state().terminal()) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("delegation did not terminate: " + service.snapshot(id));
    }

    private List<Object> delegationEvents() {
        return parent.events().stream()
                .map(logged -> logged.event())
                .filter(event -> event instanceof DelegationSnapshotEvent)
                .map(event -> (Object) event)
                .toList();
    }

    private static SessionPersistence stubPersistence() {
        return new SessionPersistence() {
            @Override
            public void save(Session session) {
                // 桩：持久化机制 it30 双宿主探针已证；本 rig 靶向生命周期
            }

            @Override
            public Loaded load(Id<Session> id) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public Catalog list(int max) {
                throw new UnsupportedOperationException("stub");
            }
        };
    }
}
