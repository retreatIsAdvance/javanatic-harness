package io.javanatic.harness.tools;

import io.javanatic.harness.kernel.events.EventKey;

/** 工具域的事件键（executor 固定触发的两道 waterfall）。 */
public final class ToolEvents {

    /** 执行前裁决：可否决（veto）或改写后放行。args = [ToolUseBlock, AbortSignal]。 */
    public static final EventKey<ToolExecutionPlan> PRE_EXECUTE =
        EventKey.waterfall("tools/pre-execute", ToolExecutionPlan.class);

    /** 执行后观察/改写结果（大输出截断等）。args = [ToolUseBlock, ToolExecutionResult]。 */
    public static final EventKey<ToolExecutionResult> POST_EXECUTE =
        EventKey.waterfall("tools/post-execute", ToolExecutionResult.class);

    private ToolEvents() {
    }
}
