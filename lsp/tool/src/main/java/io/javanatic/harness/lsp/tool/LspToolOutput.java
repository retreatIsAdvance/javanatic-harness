package io.javanatic.harness.lsp.tool;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.javanatic.harness.lsp.LspDiagnostic;
import io.javanatic.harness.lsp.LspException;
import io.javanatic.harness.lsp.LspLocation;
import io.javanatic.harness.lsp.LspObservation;
import io.javanatic.harness.lsp.LspResult;

/**
 * 四态 + 异常到结果文本的逐字映射（停 2 §8 措辞纪律，停 3 评审重点 ①）：
 * 空结果只称「拉取时点无诊断 / 本次请求返回 0 个位置」；禁词面措辞不得出现
 * （逐字模板由测试钉死，突变替换禁词必红）。
 * 锚（项目 hash 前 8 位）恒在首行——观察时点随结果文本进入 tool/result content。
 *
 * <p>预算：条数帽截断追加「…（截断）」；字节帽按 UTF-8 完整字符边界截断并追加
 * {@code " (output truncated)"}（标记不计入上限）；锚首行恒完整保留，不参与截断。
 */
final class LspToolOutput {

    private static final String COUNT_MARKER = "…（截断）";
    private static final String BYTE_MARKER = " (output truncated)";

    private LspToolOutput() {
    }

    /** LspException → 带稳定码的错误文本（工具层转 error 结果，不再包装）。 */
    static String error(LspException e) {
        return "LSP 错误（" + e.code() + "）：" + e.getMessage();
    }

    static String diagnostics(LspResult<LspDiagnostic> result, LspToolConfig config) {
        return switch (result) {
            case LspResult.Observed<LspDiagnostic> observed -> observedDiagnostics(observed, config);
            case LspResult.NotReady<LspDiagnostic> notReady ->
                "未就绪（" + notReady.reason() + "）——不是诊断结果。";
            case LspResult.Stale<LspDiagnostic> stale -> stale(stale);
            case LspResult.TimedOut<LspDiagnostic> timedOut -> timedOut(timedOut);
        };
    }

    static String locations(LspResult<LspLocation> result, LspToolConfig config) {
        return switch (result) {
            case LspResult.Observed<LspLocation> observed -> observedLocations(observed, config);
            case LspResult.NotReady<LspLocation> notReady ->
                "未就绪（" + notReady.reason() + "）——不是位置结果。";
            case LspResult.Stale<LspLocation> stale -> stale(stale);
            case LspResult.TimedOut<LspLocation> timedOut -> timedOut(timedOut);
        };
    }

    private static String observedDiagnostics(LspResult.Observed<LspDiagnostic> observed,
                                              LspToolConfig config) {
        LspObservation observation = observed.observation();
        String hash8 = observation.after().projectHash().substring(0, 8);
        if (observed.items().isEmpty()) {
            return switch (observation.evidence().origin()) {
                case QUIET_WINDOW -> "拉取成功（hash " + hash8
                    + "）、项目代稳定（前后锚相同）、完整等待窗（elapsed "
                    + oneDecimal(observation.elapsedWait()) + "s ≥ required "
                    + oneDecimal(observation.requiredWait()) + "s），拉取时点无诊断。";
                case PUBLISHED_DIAGNOSTICS ->
                    "拉取成功（hash " + hash8 + "）；LS 发布空诊断批次（0 条）。";
                case REQUEST_RESPONSE -> throw new IllegalArgumentException(
                    "diagnostics observation cannot originate from REQUEST_RESPONSE");
            };
        }
        String header = "拉取成功（hash " + hash8 + "，项目代 "
            + observation.after().generation() + "，等待窗 "
            + oneDecimal(observation.elapsedWait()) + "s）；诊断 "
            + observed.items().size() + " 条：";
        List<String> lines = new ArrayList<>();
        for (LspDiagnostic diagnostic : observed.items()) {
            String rel = relative(observation.after().projectRoot(),
                observation.after().document().uri());
            StringBuilder line = new StringBuilder("  ").append(rel).append(':')
                .append(diagnostic.range().start().line()).append(':')
                .append(diagnostic.range().start().character()).append(" [")
                .append(diagnostic.severity()).append("] ").append(diagnostic.message());
            diagnostic.code().ifPresent(code -> line.append(" (").append(code).append(')'));
            lines.add(line.toString());
        }
        return render(header, lines, config.maxDiagnostics(), config.maxOutputBytes());
    }

    private static String observedLocations(LspResult.Observed<LspLocation> observed,
                                            LspToolConfig config) {
        LspObservation observation = observed.observation();
        String hash8 = observation.after().projectHash().substring(0, 8);
        if (observed.items().isEmpty()) {
            return "拉取成功（hash " + hash8 + "）；本次请求返回 0 个位置。";
        }
        String header = "拉取成功（hash " + hash8 + "）；位置 " + observed.items().size() + " 条：";
        List<String> lines = new ArrayList<>();
        for (LspLocation location : observed.items()) {
            String rel = relative(observation.after().projectRoot(), location.uri());
            lines.add("  " + rel + ':' + location.range().start().line() + ':'
                + location.range().start().character() + '–'
                + location.range().end().line() + ':' + location.range().end().character());
        }
        return render(header, lines, config.maxLocations(), config.maxOutputBytes());
    }

    /** 首行（锚）恒保留；条数帽先行，字节帽只施加条目文本；两个截断标记追加在末。 */
    private static String render(String header, List<String> lines, int maxItems, int maxBytes) {
        boolean countTruncated = lines.size() > maxItems;
        List<String> kept = countTruncated ? lines.subList(0, maxItems) : lines;
        String body = String.join("\n", kept);
        boolean byteTruncated = utf8Bytes(body) > maxBytes;
        if (byteTruncated) {
            body = cutAtUtf8Boundary(body, maxBytes);
        }
        StringBuilder text = new StringBuilder(header).append('\n').append(body);
        if (countTruncated) {
            text.append('\n').append(COUNT_MARKER);
        }
        if (byteTruncated) {
            text.append(BYTE_MARKER);
        }
        return text.toString();
    }

    private static String stale(LspResult.Stale<?> stale) {
        return "观察过期（前后锚不同：" + stale.before().projectHash().substring(0, 8)
            + " → " + stale.after().projectHash().substring(0, 8)
            + "）——不包装为本次观察结果。";
    }

    private static String timedOut(LspResult.TimedOut<?> timedOut) {
        return "超时（" + seconds(timedOut.timeout()) + "s）——不是无诊断。";
    }

    /** 等待窗时长固定一位小数（16s → "16.0"）；总期限整数秒不带小数（30s → "30"）。 */
    private static String oneDecimal(Duration duration) {
        return String.format(Locale.ROOT, "%.1f", duration.toMillis() / 1000.0);
    }

    private static String seconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? Long.toString(millis / 1000)
            : String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    /** 文档 URI 相对项目根显示；无法相对化（Provider 围栏外）时诚实回退完整 URI。 */
    private static String relative(URI root, URI document) {
        URI rel = root.relativize(document);
        return rel.isAbsolute() ? document.toString() : rel.toString();
    }

    private static int utf8Bytes(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            bytes += codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            i += Character.charCount(codePoint);
        }
        return bytes;
    }

    /** UTF-8 完整字符边界截断：宁少截不劈开多字节字符。 */
    private static String cutAtUtf8Boundary(String text, int maxBytes) {
        int bytes = 0;
        int end = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            i += Character.charCount(codePoint);
            end = i;
        }
        return text.substring(0, end);
    }
}
