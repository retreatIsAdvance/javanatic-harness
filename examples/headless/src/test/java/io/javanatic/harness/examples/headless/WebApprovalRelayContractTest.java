package io.javanatic.harness.examples.headless;

import io.javanatic.harness.interaction.approval.ApprovalAskPlugin;
import io.javanatic.harness.kernel.plugin.PluginLoader;
import io.javanatic.harness.kernel.scope.Runtime;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStorePlugin;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.session.message.ToolUseBlock;
import io.javanatic.harness.tools.ToolDefinition;
import io.javanatic.harness.tools.ToolExecutionResult;
import io.javanatic.harness.tools.ToolExecutor;
import io.javanatic.harness.tools.ToolRegistry;
import io.javanatic.harness.tools.ToolsPlugin;
import io.javanatic.harness.tools.ValueSchema;
import io.javanatic.harness.workbench.rpc.ApprovalRelay;
import io.javanatic.harness.workbench.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 停 1 契约集成：生产 RelayApprovalPrompt + ApprovalAskPlugin + ToolExecutor，不调用模型。
 * 不代表停 2/3 的 WebChatControl/serve 接线；300s 唯一慢哨兵须显式启用。
 */
class WebApprovalRelayContractTest {

    @Test
    @Timeout(330)
    @EnabledIfSystemProperty(named = "it38.realClock", matches = "true")
    void nonInteractiveDefaultWaitsReal300SecondsThenDeniesTool() throws Exception {
        assertTimeoutDeniesTool(-1, 300);
    }

    @Test
    @Timeout(15)
    void configuredOneSecondOverridesNonInteractiveDefault() throws Exception {
        assertTimeoutDeniesTool(1, 1);
    }

    private static void assertTimeoutDeniesTool(long configuredSeconds, long expectedSeconds) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var sessionId = Session.newId("relay-contract");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); ApprovalRelay relay = new ApprovalRelay();
                Runtime rt = new Runtime();
                RelayApprovalPrompt prompt = new RelayApprovalPrompt(relay, sessionId, 0, configuredSeconds, false);
                var subscription = prompt.subscribe()) {
            subscription.take();
            new PluginLoader().loadAll(rt, List.of(new SessionStorePlugin(),
                    new ApprovalAskPlugin(prompt), new ToolsPlugin()));
            rt.root().require(ToolRegistry.KEY).register(rt.root(), ToolDefinition.of(
                    "probe", "无副作用计数探针", new ValueSchema.Object("参数", Map.of()),
                    (args, context) -> {
                        executions.incrementAndGet();
                        return ToolExecutionResult.success("executed");
                    }));
            long begin = System.nanoTime();
            var execution = executor.submit(() -> rt.root().require(ToolExecutor.KEY).execute(
                    List.of(new ToolUseBlock(CallId.of("approval-probe"), "probe", "{}")),
                    Session.create(sessionId, null, null),
                    0, 0, rt.root(), AbortSignal.never()).getFirst().event());
            var issued = subscription.poll(Duration.ofSeconds(5)).orElseThrow().pending().getFirst();
            var result = execution.get(expectedSeconds + 10, TimeUnit.SECONDS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(expectedSeconds))
                    .isLessThan(Duration.ofSeconds(expectedSeconds + 15));
            assertThat(result.block().isError()).isTrue();
            assertThat(result.block().content()).contains("denied by human gate");
            assertThat(executions.get()).isZero();
            assertThat(prompt.snapshot().pending()).isEmpty();
            assertThat(relay.pendingCount()).isZero();
            assertThatThrownBy(() -> relay.settle(issued.requestId(), ApprovalRelay.Decision.ALLOW))
                    .isInstanceOfSatisfying(RpcException.class,
                            error -> assertThat(error.code()).isEqualTo(-32009));
            assertThat(executions.get()).isZero();
            System.out.printf("RELAY_REAL_CLOCK configuredSeconds=%d elapsed=%s toolExecutions=%d lateAllow=-32009%n",
                    configuredSeconds, elapsed, executions.get());
        }
    }
}
