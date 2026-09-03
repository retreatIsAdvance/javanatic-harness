package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 五段 pipeline（R2 的落点）：审计落账 tool/call → 批内去重 → pre-execute
 * waterfall（可否决）→ 审批（固定 stage，R4）→ 执行 → post-execute waterfall
 * → 审计落账 tool/result。成功/失败/否决/拒绝全部无条件成对落账——工具实现
 * 无写日志入口，结构上无法「执行了但不留痕」。
 */
final class ToolExecutorImpl implements ToolExecutor {

    private final ToolRegistry registry;
    private final ApprovalService approval;
    private final Events events;
    private final Scope origin;

    ToolExecutorImpl(ToolRegistry registry, ApprovalService approval, Events events, Scope origin) {
        this.registry = registry;
        this.approval = approval;
        this.events = events;
        this.origin = origin;
    }

    @Override
    public List<LoggedEvent<ToolResultEvent>> execute(List<ToolUseBlock> calls, Session session,
                                                      int turn, int step, AbortSignal signal) {
        Set<Id<CallId>> batchIds = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<LoggedEvent<ToolResultEvent>>> futures = new ArrayList<>(calls.size());
        for (ToolUseBlock call : calls) {
            CompletableFuture<LoggedEvent<ToolResultEvent>> future = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    future.complete(executeOne(call, session, turn, step, signal, batchIds));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            futures.add(future);
        }
        List<LoggedEvent<ToolResultEvent>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<LoggedEvent<ToolResultEvent>> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime; // AbortedException 等取消语义原样上抛
                }
                throw new IllegalStateException("tool execution failed", e.getCause());
            }
        }
        return results;
    }

    private LoggedEvent<ToolResultEvent> executeOne(ToolUseBlock call, Session session,
                                                    int turn, int step, AbortSignal signal,
                                                    Set<Id<CallId>> batchIds) {
        // 1. 审计落账：先于一切裁决——尝试本身即事实
        session.append(new ToolCallEvent(System.currentTimeMillis(), turn, step,
            call.id(), call.name(), call.arguments()));
        if (!batchIds.add(call.id())) {
            return appendResult(call, session, turn, step,
                ToolExecutionResult.error("Duplicate callId in batch: " + call.id()));
        }
        try {
            // 2. pre-execute：可否决/改写
            ToolExecutionPlan plan = events.waterfall(ToolEvents.PRE_EXECUTE, origin, this,
                List.of(call, signal), none -> ToolExecutionPlan.proceed(call));
            if (plan.vetoed()) {
                return appendResult(call, session, turn, step,
                    ToolExecutionResult.error("vetoed: " + plan.vetoReason()));
            }
            // 3. 审批（固定 stage；拒绝 → error result，不炸 turn）
            ApprovalService.ApprovalRequest request = new ApprovalService.ApprovalRequest(
                call.name(), call.name() + " " + call.arguments(), call.arguments());
            approval.require(request);
            // 4. 执行（未知工具与异常 → error result；错误即数据）
            ToolDefinition tool = registry.resolve(call.name()).orElse(null);
            if (tool == null) {
                return appendResult(call, session, turn, step,
                    ToolExecutionResult.error("Unknown tool: " + call.name()));
            }
            ToolExecutionResult result = tool.tool().execute(
                ToolArgs.parse(call.arguments(), tool.parameters()), new ToolExecutionContext(signal));
            // 5. post-execute：观察/改写结果
            ToolExecutionResult finalResult = events.waterfall(ToolEvents.POST_EXECUTE, origin, this,
                List.of(call, result), none -> result);
            return appendResult(call, session, turn, step, finalResult);
        } catch (AbortedException e) {
            throw e; // 取消向上传播（turn 收敛），不落成 error result
        } catch (ApprovalDeniedException e) {
            return appendResult(call, session, turn, step,
                ToolExecutionResult.error("denied: " + e.getMessage()));
        } catch (Exception e) {
            return appendResult(call, session, turn, step, ToolExecutionResult.error(e));
        }
    }

    private LoggedEvent<ToolResultEvent> appendResult(ToolUseBlock call, Session session,
                                                      int turn, int step, ToolExecutionResult result) {
        return session.append(new ToolResultEvent(System.currentTimeMillis(), turn, step,
            new ToolResultBlock(call.id(), result.content(), result.isError()),
            false, new SurfaceOp.Append(), null));
    }
}
