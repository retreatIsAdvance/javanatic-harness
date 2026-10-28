package io.javanatic.harness.web.tool;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 手写 HTML → 文本提取器（T5）：单遍线性扫描，无递归、无 DOM、零依赖。
 *
 * <p>规则（与 plan 一致，刻意不是 HTML5 解析器）：
 * <ul>
 *   <li>移除 {@code script}/{@code style}/{@code noscript}/{@code template} 整块，
 *       以及注释、DOCTYPE、CDATA、处理指令；大小写不敏感。</li>
 *   <li>标签扫描跳过引号内的 {@code >}（属性值不误判结束）；未闭合标签 fail-soft
 *       丢弃余下源码（不抛出）。</li>
 *   <li>块级边界 → 空行；{@code br} → 单换行；{@code li} → {@code - }；
 *       {@code td}/{@code th} → {@code  | }；{@code tr} → 换行；{@code h1}–{@code h6}
 *       → {@code #} 前缀；{@code pre} → 围栏代码块并保留原始空白。</li>
 *   <li>固定实体集（五个基本实体 + nbsp/mdash/ndash/hellip/copy/reg + 十进制/十六进制
 *       数字实体）；未知实体原样保留（非完整 HTML5 实体表）。</li>
 *   <li>普通文本折叠空白（pre 除外）；CRLF 归一为 LF。</li>
 * </ul>
 *
 * <p>源码先按 {@code maxSourceChars} 截断（截到标签中间也 fail-soft）——截断是
 * 内容事实，以 {@link Result#truncated()} 外显。
 */
final class HtmlTextExtractor {

    /** 提取结果：文本与是否因源码上限截断。 */
    record Result(String text, boolean truncated) {
    }

    private static final Set<String> REMOVED =
        Set.of("script", "style", "noscript", "template");

    private static final Set<String> BLOCK = Set.of(
        "address", "article", "aside", "blockquote", "body", "dd", "details", "dialog",
        "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "head",
        "header", "hgroup", "html", "main", "nav", "ol", "p", "section", "summary",
        "table", "tbody", "tfoot", "thead", "title", "ul");

    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    private static final Set<String> VOID = Set.of(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr");

    /** 实体候选的最大长度（含 {@code &} 与 {@code ;}）——超长视为未知，原样保留。 */
    private static final int MAX_ENTITY_LENGTH = 32;

    private final String source;
    private final StringBuilder out = new StringBuilder();
    private int index;
    private int pendingBreaks;
    private boolean pendingSpace;
    private boolean pre;
    private boolean rowHasCell;

    private HtmlTextExtractor(String source) {
        this.source = source;
    }

    /**
     * @param html 原始 HTML（非 null）
     * @param maxSourceChars 源码字符上限（正数；超出即截断并置位）
     * @return 提取文本与截断事实
     */
    static Result extract(String html, int maxSourceChars) {
        Objects.requireNonNull(html, "html");
        if (maxSourceChars <= 0) {
            throw new IllegalArgumentException("maxSourceChars must be positive: " + maxSourceChars);
        }
        String normalized = html.replace("\r\n", "\n").replace('\r', '\n');
        boolean truncated = normalized.length() > maxSourceChars;
        String capped = truncated ? normalized.substring(0, maxSourceChars) : normalized;
        return new HtmlTextExtractor(capped).scan(truncated);
    }

    private Result scan(boolean sourceTruncated) {
        int length = source.length();
        while (index < length) {
            int lt = source.indexOf('<', index);
            if (lt < 0) {
                appendText(index, length);
                index = length;
                break;
            }
            if (lt > index) {
                appendText(index, lt);
            }
            index = lt;
            if (source.startsWith("<!--", index)) {
                int end = source.indexOf("-->", index + 4);
                index = end < 0 ? length : end + 3;
                continue;
            }
            if (source.regionMatches(true, index, "<![CDATA[", 0, 9)) {
                int end = source.indexOf("]]>", index + 9);
                index = end < 0 ? length : end + 3;
                continue;
            }
            if (source.startsWith("<!", index) || source.startsWith("<?", index)) {
                int end = source.indexOf('>', index + 2);
                index = end < 0 ? length : end + 1;
                continue;
            }
            int cursor = index + 1;
            boolean closing = cursor < length && source.charAt(cursor) == '/';
            if (closing) {
                cursor++;
            }
            int nameStart = cursor;
            while (cursor < length && isNameChar(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor == nameStart) {
                emitMarker("<"); // 字面 '<'（不是标签）
                index++;
                continue;
            }
            String name = source.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
            int tagEnd = findTagEnd(cursor);
            if (tagEnd < 0) {
                index = length; // 未闭合标签：fail-soft 丢弃余下
                break;
            }
            boolean selfClosing = !closing && source.charAt(tagEnd - 1) == '/';
            index = tagEnd + 1;
            if (!closing && !selfClosing && REMOVED.contains(name)) {
                int afterClose = findClosingTag(name, index);
                index = afterClose < 0 ? length : afterClose;
                continue;
            }
            handleTag(name, closing, selfClosing);
        }
        if (pendingBreaks > 0) {
            pendingBreaks = 0;
            pendingSpace = false;
        }
        return new Result(trimEnd(out), sourceTruncated);
    }

    /** 引号感知的标签结束位置（不含嵌套 {@code <} 语义；属性值里的 {@code >} 不算）。 */
    private int findTagEnd(int from) {
        char quote = 0;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /** 查找对应闭合标签；返回 {@code >} 之后的位置，未找到返回 -1。 */
    private int findClosingTag(String name, int from) {
        int length = source.length();
        for (int i = from; i + 2 + name.length() <= length; i++) {
            if (source.charAt(i) != '<' || source.charAt(i + 1) != '/') {
                continue;
            }
            int nameStart = i + 2;
            if (!source.regionMatches(true, nameStart, name, 0, name.length())) {
                continue;
            }
            int after = nameStart + name.length();
            if (after < length && isNameChar(source.charAt(after))) {
                continue;
            }
            int end = source.indexOf('>', after);
            return end < 0 ? -1 : end + 1;
        }
        return -1;
    }

    private void handleTag(String name, boolean closing, boolean selfClosing) {
        if (closing) {
            if (HEADINGS.contains(name) || BLOCK.contains(name)) {
                blockBreak();
            } else if (name.equals("li")) {
                lineBreak();
            } else if (name.equals("tr")) {
                lineBreak();
                rowHasCell = false;
            } else if (name.equals("pre") && pre) {
                pre = false;
                pendingBreaks = 0;
                pendingSpace = false;
                emitMarker("\n```");
                blockBreak();
            }
            return;
        }
        if (selfClosing || VOID.contains(name)) {
            if (name.equals("br")) {
                lineBreak();
            } else if (name.equals("hr")) {
                blockBreak();
            }
            return;
        }
        if (HEADINGS.contains(name)) {
            blockBreak();
            emitMarker("#".repeat(name.charAt(1) - '0'));
            emitMarker(" ");
        } else if (name.equals("pre")) {
            if (!pre) {
                blockBreak();
                emitMarker("```\n");
                pendingBreaks = 0;
                pendingSpace = false;
                pre = true;
            }
        } else if (name.equals("li")) {
            lineBreak();
            emitMarker("- ");
        } else if (name.equals("tr")) {
            lineBreak();
            rowHasCell = false;
        } else if (name.equals("td") || name.equals("th")) {
            if (rowHasCell) {
                emitMarker(" | ");
            }
            rowHasCell = true;
        } else if (BLOCK.contains(name)) {
            blockBreak();
        }
    }

    private void appendText(int from, int to) {
        int i = from;
        while (i < to) {
            char c = source.charAt(i);
            if (c == '&') {
                int afterEntity = decodeEntity(i, to);
                if (afterEntity > 0) {
                    i = afterEntity;
                    continue;
                }
            }
            int codePoint = source.codePointAt(i);
            emitCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
    }

    /**
     * 解码实体：成功时已输出并返回分号之后位置；未知/越界返回 -1（调用方原样
     * 输出 {@code &}）。
     */
    private int decodeEntity(int start, int to) {
        int limit = Math.min(to, start + MAX_ENTITY_LENGTH);
        int semicolon = source.indexOf(';', start + 1);
        if (semicolon < 0 || semicolon >= limit) {
            return -1;
        }
        String entity = source.substring(start + 1, semicolon);
        if (entity.isEmpty()) {
            return -1;
        }
        if (entity.charAt(0) == '#') {
            int codePoint = numericEntity(entity);
            if (codePoint < 0) {
                return -1;
            }
            emitCodePoint(codePoint);
            return semicolon + 1;
        }
        int decoded = namedEntity(entity);
        if (decoded < 0) {
            return -1;
        }
        emitCodePoint(decoded);
        return semicolon + 1;
    }

    private static int numericEntity(String entity) {
        boolean hex = entity.length() > 1
            && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X');
        String digits = entity.substring(hex ? 2 : 1);
        if (digits.isEmpty() || digits.length() > 8) {
            return -1;
        }
        long value;
        try {
            value = Long.parseLong(digits, hex ? 16 : 10);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (value <= 0 || value > Character.MAX_CODE_POINT) {
            return -1;
        }
        if (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE) {
            return -1; // 孤立代理项不是合法字符
        }
        return (int) value;
    }

    private static int namedEntity(String entity) {
        return switch (entity.toLowerCase(Locale.ROOT)) {
            case "amp" -> '&';
            case "lt" -> '<';
            case "gt" -> '>';
            case "quot" -> '"';
            case "apos" -> '\'';
            case "nbsp" -> '\u00A0';
            case "mdash" -> '\u2014';
            case "ndash" -> '\u2013';
            case "hellip" -> '\u2026';
            case "copy" -> '\u00A9';
            case "reg" -> '\u00AE';
            default -> -1;
        };
    }

    private void emitCodePoint(int codePoint) {
        if (pre) {
            out.appendCodePoint(codePoint);
            return;
        }
        if (isSpace(codePoint)) {
            pendingSpace = true;
            return;
        }
        flushPending();
        out.appendCodePoint(codePoint);
    }

    private void emitMarker(String marker) {
        flushPending();
        out.append(marker);
    }

    private void blockBreak() {
        pendingBreaks = Math.max(pendingBreaks, 2);
        pendingSpace = false;
    }

    private void lineBreak() {
        pendingBreaks = Math.max(pendingBreaks, 1);
        pendingSpace = false;
    }

    private void flushPending() {
        if (out.length() == 0) {
            pendingBreaks = 0;
            pendingSpace = false;
            return;
        }
        if (pendingBreaks > 0) {
            trimTrailingSpaces();
            int existing = trailingNewlines();
            for (int i = existing; i < pendingBreaks; i++) {
                out.append('\n');
            }
            pendingBreaks = 0;
            pendingSpace = false;
        } else if (pendingSpace) {
            if (!isSpace(out.charAt(out.length() - 1))) {
                out.append(' ');
            }
            pendingSpace = false;
        }
    }

    private void trimTrailingSpaces() {
        while (out.length() > 0) {
            char last = out.charAt(out.length() - 1);
            if (last == ' ' || last == '\t' || last == '\u00A0') {
                out.setLength(out.length() - 1);
            } else {
                return;
            }
        }
    }

    private int trailingNewlines() {
        int count = 0;
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\n'; i--) {
            count++;
        }
        return count;
    }

    private static String trimEnd(StringBuilder text) {
        int end = text.length();
        while (end > 0 && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static boolean isSpace(int codePoint) {
        return codePoint == ' ' || codePoint == '\t' || codePoint == '\n'
            || codePoint == '\r' || codePoint == '\f' || codePoint == '\u00A0';
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '-' || c == ':';
    }
}
