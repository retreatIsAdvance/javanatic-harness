package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Files.* 的直接包装：阻塞语义、fail loud。根目录限制在此强制(生产策略,05 §4)：真实路径围栏,符号链接不豁免。有界读取/列举/搜索防大输出撑爆内存与上下文(it20/it21)。 */
public final class LocalFs implements FsService {

    /** 单次读取/编辑的字节上限（文档化默认：256 KiB，与 shell-bash-local `maxOutputBytes` 对称）。 */
    public static final long DEFAULT_MAX_READ_BYTES = 256 * 1024;

    /** 单次列举的条目上限（文档化默认）。 */
    public static final int DEFAULT_MAX_LIST_ENTRIES = 1000;

    /** 单次搜索的匹配上限（文档化默认；超限以 SearchResult.truncated 承载）。 */
    public static final int DEFAULT_MAX_SEARCH_MATCHES = 200;

    /** 多处匹配拒绝消息里最多列出的行号数（消息进模型上下文，须有界）。 */
    private static final int MAX_REPORTED_LINES = 10;

    /** 单条搜索匹配的行文本上限（字符；超出截断加省略号——结果进模型上下文）。 */
    private static final int MAX_MATCH_TEXT = 200;

    /** 二进制探测窗口（字节）：窗口内含 NUL 即视为二进制跳过（不猜编码）。 */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;

    private final Path root;
    private final long maxReadBytes;
    private final int maxListEntries;
    private final int maxSearchMatches;

    /** @param root 工作区根(绝对且须已存在);构造期取 realpath 归一,作围栏基准 */
    public LocalFs(Path root) {
        this(root, DEFAULT_MAX_READ_BYTES, DEFAULT_MAX_LIST_ENTRIES, DEFAULT_MAX_SEARCH_MATCHES);
    }

    /**
     * @param root           工作区根(绝对且须已存在)
     * @param maxReadBytes   单次读取/编辑的字节上限(正数;超限读取截断、编辑 fail loud)
     * @param maxListEntries 单次列举的条目上限(正数;超限截断以 Listing.truncated 承载)
     */
    public LocalFs(Path root, long maxReadBytes, int maxListEntries) {
        this(root, maxReadBytes, maxListEntries, DEFAULT_MAX_SEARCH_MATCHES);
    }

    /**
     * @param root             工作区根(绝对且须已存在)
     * @param maxReadBytes     单次读取/编辑的字节上限(正数;超限读取截断、编辑 fail loud)
     * @param maxListEntries   单次列举的条目上限(正数;超限截断以 Listing.truncated 承载)
     * @param maxSearchMatches 单次搜索的匹配上限(正数;超限截断以 SearchResult.truncated 承载)
     */
    public LocalFs(Path root, long maxReadBytes, int maxListEntries, int maxSearchMatches) {
        Objects.requireNonNull(root, "root");
        if (maxReadBytes <= 0 || maxReadBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maxReadBytes must be in [1, " + Integer.MAX_VALUE + "]: " + maxReadBytes);
        }
        if (maxListEntries <= 0) {
            throw new IllegalArgumentException("maxListEntries must be positive: " + maxListEntries);
        }
        if (maxSearchMatches <= 0) {
            throw new IllegalArgumentException("maxSearchMatches must be positive: " + maxSearchMatches);
        }
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute: " + root);
        }
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("root must exist (realpath normalization failed): " + root, e);
        }
        this.maxReadBytes = maxReadBytes;
        this.maxListEntries = maxListEntries;
        this.maxSearchMatches = maxSearchMatches;
    }

    /**
     * 路径解析:相对路径按 root 解析;候选路径真实化(最深已存在祖先取 realpath 后拼回尾段),
     * 再按真实路径做围栏判定——符号链接在判定前被展开,别名无法绕过。
     *
     * @throws IllegalArgumentException 越界(../ 逃逸、root 外绝对路径、指向 root 外的符号链接)时
     * @throws IOException 真实化失败(如悬空符号链接)时
     */
    private Path resolve(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path candidate = (path.isAbsolute() ? path : root.resolve(path))
            .toAbsolutePath().normalize();
        Path resolved = realPathOfDeepestExisting(candidate);
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace root: " + path);
        }
        return resolved;
    }

    /**
     * 真实化:自候选路径上溯至最深已存在段(末段不跟随符号链接),取其 realpath 再拼回尾段。
     * 悬空符号链接在此 fail loud,而非被穿透(写逃逸)或以名义路径放行。
     */
    private static Path realPathOfDeepestExisting(Path candidate) throws IOException {
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("no existing ancestor for path: " + candidate);
        }
        return existing.toRealPath().resolve(existing.relativize(candidate));
    }

    @Override
    public String read(Path path) throws IOException {
        Path resolved = resolve(path);
        byte[] bytes;
        boolean truncated;
        try (InputStream in = Files.newInputStream(resolved)) {
            bytes = in.readNBytes((int) maxReadBytes);
            truncated = in.read() != -1;
        }
        if (!truncated) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        // 截断点可能落在多字节字符中间:回退到最后一个完整 UTF-8 序列的末尾
        int length = completeUtf8Prefix(bytes);
        return new String(bytes, 0, length, StandardCharsets.UTF_8) + READ_TRUNCATED_MARKER;
    }

    /** 从尾部回退最多 3 个字节找到完整 UTF-8 序列边界;尾部本就完整或非法输入时不动。 */
    private static int completeUtf8Prefix(byte[] bytes) {
        int end = bytes.length;
        for (int back = 0; back < 4 && back < end; back++) {
            int b = bytes[end - back - 1] & 0xFF;
            if ((b & 0xC0) == 0x80) {
                continue;   // 延续字节:继续向前找序列首字节
            }
            int need;
            if (b < 0x80) {
                need = 1;
            } else if (b >= 0xF0) {
                need = 4;
            } else if (b >= 0xE0) {
                need = 3;
            } else {
                need = 2;
            }
            return need == back + 1 ? end : end - back - 1;
        }
        return end;
    }

    @Override
    public void write(Path path, String content) throws IOException {
        Path resolved = resolve(path);
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        Files.writeString(resolved, content);
    }

    @Override
    public String edit(Path path, String oldString, String newString) throws IOException {
        Path resolved = resolve(path);
        // 空串在任意位置都「匹配」:无消歧语义,拒绝而非插入到位置 0
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("oldString must not be empty: " + path);
        }
        // 编辑是整文件读+写+返回:超限即拒(fail loud,不静默截断写入)
        long size = Files.size(resolved);
        if (size > maxReadBytes) {
            throw new IllegalArgumentException("file too large to edit: " + size
                + " bytes exceeds maxReadBytes " + maxReadBytes + ": " + path);
        }
        String content = Files.readString(resolved);
        int at = content.indexOf(oldString);
        if (at < 0) {
            throw new IllegalArgumentException("oldString not found in " + path);
        }
        if (content.indexOf(oldString, at + oldString.length()) >= 0) {
            throw new IllegalArgumentException(notUniqueMessage(content, oldString, path));
        }
        String edited = content.substring(0, at) + newString
            + content.substring(at + oldString.length());
        Files.writeString(resolved, edited);
        return edited;
    }

    /** 多处匹配的拒绝消息(it21):总处数(非重叠计数) + 有界行号列表(1 起,同行只报一次)。 */
    private static String notUniqueMessage(String content, String oldString, Path path) {
        int count = 0;
        int reported = 0;
        long line = 1;
        int scanned = 0;
        long lastReported = -1;
        boolean more = false;
        StringBuilder lines = new StringBuilder();
        for (int at = content.indexOf(oldString); at >= 0;
                at = content.indexOf(oldString, at + oldString.length())) {
            count++;
            for (int i = scanned; i < at; i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            scanned = at;
            if (line != lastReported) {
                if (reported < MAX_REPORTED_LINES) {
                    reported++;
                    lastReported = line;
                    lines.append(lines.isEmpty() ? "" : ", ").append(line);
                } else {
                    more = true;
                }
            }
        }
        return "oldString is not unique in " + path + ": " + count + " occurrences (lines "
            + lines + (more ? ", …" : "") + ") — add surrounding context to disambiguate";
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(resolve(path));
    }

    @Override
    public Listing list(Path path) throws IOException {
        try (var stream = Files.list(resolve(path))) {
            List<Path> children = stream
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
            boolean truncated = children.size() > maxListEntries;
            List<DirEntry> entries = children.subList(0, Math.min(children.size(), maxListEntries))
                .stream()
                .map(child -> new DirEntry(child.getFileName().toString(), Files.isDirectory(child)))
                .toList();
            return new Listing(entries, truncated);
        }
    }

    /**
     * 字面逐行搜索:起始路径经围栏解析后走查(不跟随符号链接——目录不进、文件不读,
     * 符号链接条目的属性按 NOFOLLOW 读取,故被 {@code isRegularFile()} 滤掉);
     * 二进制(探测窗内含 NUL)与超读取上限的文件跳过;结果路径相对 root、以 {@code /} 分隔。
     */
    @Override
    public SearchResult search(String pattern, Path path) throws IOException {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        Path base = resolve(path);
        if (!Files.exists(base)) {
            throw new NoSuchFileException(base.toString());
        }
        MatchCollector collector = new MatchCollector(pattern);
        Files.walkFileTree(base, collector);
        return new SearchResult(List.copyOf(collector.matches), collector.truncated);
    }

    /**
     * 有界收集:走查序依文件系统而异,按收集序截断会不确定——维持 (路径,行号) 序最小的
     * maxSearchMatches 条,被挤出者置 truncated(结果集确定:同一棵树同一上限,输出恒定)。
     */
    private final class MatchCollector extends SimpleFileVisitor<Path> {

        private final String pattern;
        private final TreeSet<Match> matches =
            new TreeSet<>(Comparator.comparing(Match::path).thenComparingInt(Match::line));
        private boolean truncated;

        private MatchCollector(String pattern) {
            this.pattern = pattern;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (attrs.isRegularFile() && attrs.size() <= maxReadBytes) {
                scan(file);
            }
            return FileVisitResult.CONTINUE;
        }

        /** 不可读条目跳过:搜索是尽力而为的发现面,单个文件不可读不中断整树。 */
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }

        private void scan(Path file) throws IOException {
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString();
            int line = 1;
            int start = 0;
            while (true) {
                int end = content.indexOf('\n', start);
                String text = end < 0 ? content.substring(start) : content.substring(start, end);
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                if (text.contains(pattern)) {
                    offer(new Match(relative, line, abbreviate(text)));
                }
                if (end < 0) {
                    return;
                }
                start = end + 1;
                line++;
            }
        }

        private void offer(Match match) {
            if (matches.size() < maxSearchMatches) {
                matches.add(match);
                return;
            }
            truncated = true;
            if (matches.comparator().compare(match, matches.last()) < 0) {
                matches.pollLast();
                matches.add(match);
            }
        }

        /** 二进制探测:窗口内含 NUL 即视为二进制(不猜编码)。 */
        private static boolean isBinary(byte[] bytes) {
            int probe = Math.min(bytes.length, BINARY_PROBE_BYTES);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) {
                    return true;
                }
            }
            return false;
        }

        /** 行文本超上限即截断加省略号(结果进模型上下文,须有界)。 */
        private static String abbreviate(String text) {
            return text.length() <= MAX_MATCH_TEXT ? text
                : text.substring(0, MAX_MATCH_TEXT) + "…";
        }
    }
}
