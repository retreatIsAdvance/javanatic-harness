package io.javanatic.harness.fs.tool;

import io.javanatic.harness.fs.FsService;
import io.javanatic.harness.kernel.brand.Id;
import io.javanatic.harness.session.event.LoggedEvent;
import io.javanatic.harness.session.event.SessionEvent;
import io.javanatic.harness.session.event.ToolCallEvent;
import io.javanatic.harness.session.event.ToolResultEvent;
import io.javanatic.harness.session.message.CallId;
import io.javanatic.harness.tools.ToolArgs;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读后修改保护（it21）：把会话日志折叠成「路径 → 最新内容事实」——fs_edit / fs_write
 * 执行前比对磁盘当前内容，不一致即拒（外部修改），见 {@code FsToolPlugin.guard}。
 *
 * <p><b>纯 fold</b>：事实全在日志，resume/fork 不需内存镜像即恢复，零 per-Session
 * 内存态（同 {@code PlanModeService.foldActive} 与 {@code SessionRecovery.analyze} 先例）。
 * 事实来源（只认成功结果，按 seq 末值胜）：{@code fs_read} 未截断的全文结果、
 * {@code fs_edit} 返回的编辑后全文、{@code fs_write} 的 content 实参。
 * 配对靠 callId（日志交错序任意，永不靠相邻性）。
 *
 * <p><b>边界（漏报可接受，不产生误报）</b>：从未成功读过 / 只读到截断内容 /
 * 路径写法不同源（相对与绝对、不同基准）→ 无事实即无保护。事实路径按实参字符串
 * {@code Path.normalize} 归一，故 {@code ./x} 与 {@code x} 同源、{@code a/../x} 亦然。
 */
final class ReadLedger {

    private final Map<String, String> facts;

    private ReadLedger(Map<String, String> facts) {
        this.facts = facts;
    }

    /** 折叠整条日志（含 seed 前缀——resume 后的保护与重启前等价）。 */
    static ReadLedger fold(List<LoggedEvent<? extends SessionEvent>> events) {
        Map<String, String> facts = new HashMap<>();
        Map<Id<CallId>, Pending> pending = new HashMap<>();
        for (LoggedEvent<? extends SessionEvent> entry : events) {
            if (entry.event() instanceof ToolCallEvent call) {
                Pending parsed = parse(call);
                if (parsed != null) {
                    pending.put(call.callId(), parsed);
                }
            } else if (entry.event() instanceof ToolResultEvent result) {
                Pending call = pending.remove(result.block().toolUseId());
                if (call != null && !result.block().isError()) {
                    record(facts, call, result);
                }
            }
        }
        return new ReadLedger(facts);
    }

    /** @return 该路径的最新内容事实；null = 无保护（从未成功读过或只读到截断内容） */
    String factFor(Path path) {
        return facts.get(path.normalize().toString());
    }

    private static void record(Map<String, String> facts, Pending call, ToolResultEvent result) {
        switch (call.tool()) {
            case "fs_read" -> {
                String content = result.block().content();
                // 截断结果不是内容事实（尾部标记不属于文件）
                if (!content.endsWith(FsService.READ_TRUNCATED_MARKER)) {
                    facts.put(call.path(), content);
                }
            }
            case "fs_edit" -> facts.put(call.path(), result.block().content());
            case "fs_write" -> facts.put(call.path(), call.content());
            default -> { }
        }
    }

    private static Pending parse(ToolCallEvent call) {
        String tool = call.name();
        if (!tool.equals("fs_read") && !tool.equals("fs_edit") && !tool.equals("fs_write")) {
            return null;
        }
        try {
            ToolArgs args = ToolArgs.parse(call.arguments(),
                switch (tool) {
                    case "fs_read" -> FsToolPlugin.READ_ARGS;
                    case "fs_write" -> FsToolPlugin.WRITE_ARGS;
                    default -> FsToolPlugin.EDIT_ARGS;
                });
            String content = tool.equals("fs_write") ? args.readString("content") : null;
            return new Pending(tool, keyOf(args.readString("path")), content);
        } catch (IllegalArgumentException e) {
            // 日志是信任边界(08 §6):历史实参不可解析(含 Path.of 的 InvalidPathException)
            // = 无事实——漏报方向,不炸当前编辑
            return null;
        }
    }

    private static String keyOf(String raw) {
        return Path.of(raw).normalize().toString();
    }

    /** 一条待配对的调用：工具名 + 归一化路径 +（fs_write 才有）写入内容。 */
    private record Pending(String tool, String path, String content) {}
}
