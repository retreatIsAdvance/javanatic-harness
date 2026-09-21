package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Files.* 的直接包装：阻塞语义、fail loud。根目录限制在此强制(生产策略,05 §4)：真实路径围栏,符号链接不豁免。有界读取/列举防大输出撑爆内存与上下文(it20)。 */
public final class LocalFs implements FsService {

    /** 单次读取/编辑的字节上限（文档化默认：256 KiB，与 shell-bash-local `maxOutputBytes` 对称）。 */
    public static final long DEFAULT_MAX_READ_BYTES = 256 * 1024;

    /** 单次列举的条目上限（文档化默认）。 */
    public static final int DEFAULT_MAX_LIST_ENTRIES = 1000;

    private final Path root;
    private final long maxReadBytes;
    private final int maxListEntries;

    /** @param root 工作区根(绝对且须已存在);构造期取 realpath 归一,作围栏基准 */
    public LocalFs(Path root) {
        this(root, DEFAULT_MAX_READ_BYTES, DEFAULT_MAX_LIST_ENTRIES);
    }

    /**
     * @param root           工作区根(绝对且须已存在)
     * @param maxReadBytes   单次读取/编辑的字节上限(正数;超限读取截断、编辑 fail loud)
     * @param maxListEntries 单次列举的条目上限(正数;超限截断以 Listing.truncated 承载)
     */
    public LocalFs(Path root, long maxReadBytes, int maxListEntries) {
        Objects.requireNonNull(root, "root");
        if (maxReadBytes <= 0 || maxReadBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maxReadBytes must be in [1, " + Integer.MAX_VALUE + "]: " + maxReadBytes);
        }
        if (maxListEntries <= 0) {
            throw new IllegalArgumentException("maxListEntries must be positive: " + maxListEntries);
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
        String edited = content.substring(0, at) + newString
            + content.substring(at + oldString.length());
        Files.writeString(resolved, edited);
        return edited;
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
}
