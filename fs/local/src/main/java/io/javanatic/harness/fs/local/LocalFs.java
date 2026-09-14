package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Files.* 的直接包装：阻塞语义、fail loud。根目录限制在此强制(生产策略,05 §4)：真实路径围栏,符号链接不豁免。 */
public final class LocalFs implements FsService {

    private final Path root;

    /** @param root 工作区根(绝对且须已存在);构造期取 realpath 归一,作围栏基准 */
    public LocalFs(Path root) {
        Objects.requireNonNull(root, "root");
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute: " + root);
        }
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("root must exist (realpath normalization failed): " + root, e);
        }
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
        return Files.readString(resolve(path));
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
    public List<DirEntry> list(Path path) throws IOException {
        try (var stream = Files.list(resolve(path))) {
            List<DirEntry> entries = new ArrayList<>();
            for (Path child : stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                entries.add(new DirEntry(child.getFileName().toString(), Files.isDirectory(child)));
            }
            return List.copyOf(entries);
        }
    }
}
