package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.events.Events;
import io.javanatic.harness.kernel.scope.Scope;
import io.javanatic.harness.llm.AbortedException;
import io.javanatic.harness.llm.AbortSignal;
import io.javanatic.harness.session.Session;
import io.javanatic.harness.session.SessionStore;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SurfaceOp;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.message.ToolResultBlock;
import io.javanatic.harness.session.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 五段 pipeline（R2 的落点）：批前导落账全部 tool/call（含批内去重定案）→ 派发前
 * 耐久屏障 → pre-execute waterfall（可否决）→ 审批（固定 stage，R4）→ 执行 →
 * post-execute waterfall → 审计落账 tool/result。成功/失败/否决/拒绝全部无条件
 * 成对落账——审计对归 executor，工具在结构上无法「执行了但不留痕」。工具可经
 * {@link ToolExecutionContext#session()} 追加<b>领域事件</b>（非审计）；
 * Session.append 的同步与 surface 校验是既有防线。
 */
final class ToolExecutorImpl implements ToolExecutor {

    private final ToolRegistry registry;
    private final ApprovalService approval;
    private final SessionStore store;
    private final Events events;
    private final Scope origin;

    ToolExecutorImpl(ToolRegistry registry, ApprovalService approval, SessionStore store,
                     Events events, Scope origin) {
        this.registry = registry;
        this.approval = approval;
        this.store = store;
        this.events = events;
        this.origin = origin;
    }

    /** 一次批次内的执行上下文(落账与解析依据,五段 pipeline 全程携带)。 */
    private record ExecContext(Session session, int turn, int step, Scope agentScope) {
    }

    @Override
    public List<LoggedEvent<ToolResultEvent>> execute(List<ToolUseBlock> calls, Session session,
                                                      int turn, int step, Scope agentScope,
                                                      AbortSignal signal) {
        ExecContext context = new ExecContext(session, turn, step, agentScope);
        // 批前导(it19):全部 tool/call 于一切裁决与副作用之前落账,再屏障,再 fork——
        // 「尝试过」不因崩溃丢失;批内重复据此定案(输入序首个实体,其余成 error result)
        List<Boolean> duplicates = appendCalls(calls, session, turn, step);
        store.flush(agentScope, session);
        List<CompletableFuture<LoggedEvent<ToolResultEvent>>> futures = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            boolean duplicate = duplicates.get(i);
            CompletableFuture<LoggedEvent<ToolResultEvent>> future = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    future.complete(executeOne(call, context, signal, duplicate));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            futures.add(future);
        }
        // join 全部再传播(it18):先等每个 future 落定,再裁决异常——取消/失败都不许
        // 撇下仍在跑的兄弟(静止语义 = 无工具线程在跑)。选择规则:AbortedException
        // (输入序首个)优先——取消不被兄弟失败掩盖,turn 才能收敛成 Aborted;余按输入序。
        List<LoggedEvent<ToolResultEvent>> results = new ArrayList<>(futures.size());
        AbortedException firstAbort = null;
        Throwable firstFailure = null;
        for (CompletableFuture<LoggedEvent<ToolResultEvent>> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof AbortedException aborted) {
                    if (firstAbort == null) {
                        firstAbort = aborted;
                    }
                } else if (firstFailure == null) {
                    firstFailure = cause;
                }
            }
        }
        if (firstAbort != null) {
            throw firstAbort;
        }
        if (firstFailure != null) {
            if (firstFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("tool execution failed", firstFailure);
        }
        return results;
    }

    /** 批前导落账:全部 tool/call 按输入序落账,返回逐调用重复标记(首个为 false)。 */
    private static List<Boolean> appendCalls(List<ToolUseBlock> calls, Session session,
                                             int turn, int step) {
        Set<Id<CallId>> firsts = new HashSet<>();
        List<Boolean> duplicates = new ArrayList<>(calls.size());
        for (ToolUseBlock call : calls) {
            session.append(new ToolCallEvent(System.currentTimeMillis(), turn, step,
                call.id(), call.name(), call.arguments()));
            duplicates.add(!firsts.add(call.id()));
        }
        return duplicates;
    }

    private LoggedEvent<ToolResultEvent> executeOne(ToolUseBlock call, ExecContext context,
                                                    AbortSignal signal, boolean duplicate) {
        Session session = context.session();
        int turn = context.turn();
        int step = context.step();
        // 1. 批内重复(前导已定案;tool/call 已在前导落账——尝试即事实)
        if (duplicate) {
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
            // 3. 审批（固定 stage；拒绝 → error result，不炸 turn；等待中取消 → AbortedException 收敛）
            ApprovalService.ApprovalRequest request = new ApprovalService.ApprovalRequest(
                call.name(), call.name() + " " + call.arguments(), call.arguments());
            approval.require(request, signal);
            // 4. 执行（未知工具与异常 → error result；错误即数据）
            ToolDefinition tool = registry.resolve(context.agentScope(), call.name()).orElse(null);
            if (tool == null) {
                return appendResult(call, session, turn, step,
                    ToolExecutionResult.error("Unknown tool: " + call.name()));
            }
            ToolExecutionResult result = tool.tool().execute(
                ToolArgs.parse(call.arguments(), tool.parameters()),
                new ToolExecutionContext(signal, session));
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
