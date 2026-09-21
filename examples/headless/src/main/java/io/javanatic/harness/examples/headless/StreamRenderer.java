package io.javanatic.harness.examples.headless;

import io.javanatic.harness.agentloop.AssistantChunkEvent;
import io.javanatic.harness.llm.StreamChunk;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.event.TurnEnd;
import io.javanatic.harness.session.event.TurnEndReason;

import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * REPL 流式渲染器（补充 2「渲染出锁」）：APPENDED 观察者在 session monitor 内
 * 同步执行，回调只入队（不写终端、不做 IO——chunk 风暴下不拖 append 屏障）；
 * 单渲染虚拟线程按序出队写 out。映射：chunk Delta 逐块落字、Finish 收行；
 * tool/call|result 一行摘要；turn 失败按 {@link io.javanatic.harness.session.event.FailureKind}
 * 渲染可行动文案（05 §3 承诺：不解析消息文本）；turn/end 恒打一行轮末统计
 * （{@link TurnStats}，与 one-shot 出口同形）。REPL 面板输出经 {@link #println}
 * 入同一队列——屏幕只有一个写者。
 */
final class StreamRenderer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(StreamRenderer.class.getName());

    /** 单行摘要/预览上限（字符）。 */
    static final int PREVIEW = 160;

    private final PrintStream out;
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final Thread worker;

    /** 轮末统计累计器（渲染线程私有）。 */
    private final TurnStats.Accumulator stats = new TurnStats.Accumulator();

    /** 是否有未闭合的输出行；仅渲染线程读写。 */
    private boolean lineOpen;

    StreamRenderer(PrintStream out) {
        this.out = out;
        this.worker = Thread.ofVirtual().name("jh-repl-render").start(this::drain);
    }

    /** APPENDED 回调入口：只入队（映射与 IO 都在渲染线程上）。 */
    void onEvent(SessionEvent event) {
        queue.add(new Item.Event(event));
    }

    /** REPL 面板/命令输出：经同一队列保证与流式片段同序。 */
    void println(String line) {
        queue.add(new Item.Write(line + "\n"));
    }

    @Override
    public void close() {
        queue.add(new Item.Close());
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        try {
            while (true) {
                Item item = queue.take();
                if (item instanceof Item.Close) {
                    return;
                }
                if (item instanceof Item.Event event) {
                    try {
                        render(event.event());
                    } catch (RuntimeException e) {
                        // 渲染问题只丢这一条(渲染线程不倒,append 事实不受影响)
                        LOG.log(Level.WARNING, "渲染跳过: {0}", e);
                    }
                } else if (item instanceof Item.Write write) {
                    breakLine(); // 面板行不与未闭合的流式行粘连
                    write(write.text());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void render(SessionEvent event) {
        stats.on(event);
        switch (event) {
            case AssistantChunkEvent chunked -> renderChunk(chunked.chunk());
            case ToolCallEvent call -> {
                breakLine();
                write("→ " + call.name() + " " + preview(call.arguments()) + "\n");
            }
            case ToolResultEvent result -> {
                breakLine();
                write("← " + (result.block().isError() ? "error: " : "")
                    + preview(result.block().content()) + "\n");
            }
            case TurnEnd end -> renderTurnEnd(end);
            default -> { }
        }
    }

    /** 失败行先落（可行动文案），末了恒补一行轮末统计——同形见 {@link TurnStats#line()}。 */
    private void renderTurnEnd(TurnEnd end) {
        if (end.reason() instanceof TurnEndReason.Error error) {
            breakLine();
            write("turn 失败: " + failureText(error) + "\n");
        }
        breakLine();
        write(stats.finish(end).line() + "\n");
    }

    private void renderChunk(StreamChunk chunk) {
        switch (chunk) {
            case StreamChunk.Delta delta -> write(delta.text());
            case StreamChunk.Finish ignored -> breakLine();
            case StreamChunk.DeltaToolUse ignored -> { } // 工具摘要走 tool/call 落账事实
            case StreamChunk.Usage ignored -> { }
        }
    }

    private void write(String text) {
        out.print(text);
        out.flush();
        lineOpen = !text.endsWith("\n");
    }

    /** 终止未闭合的输出行（工具/失败/面板行前调用）；已在新行则无操作。 */
    private void breakLine() {
        if (lineOpen) {
            out.println();
            out.flush();
            lineOpen = false;
        }
    }

    /** 05 §3：kind 决定可行动文案，message 仅作人读附注（不参与判定）。 */
    static String failureText(TurnEndReason.Error error) {
        String action = switch (error.kind()) {
            case AUTH -> "认证失败：检查 API key（--api-key= 或 --api-key-env=）";
            case RATE_LIMIT -> "被限流：稍后重试";
            case SERVER -> "厂商服务端错误：稍后重试";
            case NETWORK -> "网络错误：检查连通性后重试";
            case TIMEOUT -> "请求超时：可重试";
            case PROTOCOL -> "协议错误：厂商响应不符合预期（细节见日志）";
            case OVERFLOW -> "上下文溢出：自动压缩后仍超出模型窗口";
            case DISK -> "会话日志写盘失败：检查磁盘/权限后重试（会话需 --resume 校验）";
            case UNKNOWN -> "失败（未分类，细节见日志）";
        };
        String detail = error.message() == null ? "" : preview(error.message());
        return detail.isEmpty() ? action : action + "（" + detail + "）";
    }

    /** 单行预览：空白折叠 + 截断（超限加省略号）。 */
    static String preview(String value) {
        String flat = value.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW) + "…";
    }

    private sealed interface Item {

        record Event(SessionEvent event) implements Item {
        }

        record Write(String text) implements Item {
        }

        record Close() implements Item {
        }
    }
}
