package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Files.* 的直接包装：阻塞语义、fail loud。根目录限制在此强制(生产策略,05 §4)。 */
public final class LocalFs implements FsService {

    private final Path root;

    /** @param root 工作区根(绝对路径);所有路径经它解析,越界 fail loud */
    public LocalFs(Path root) {
        Objects.requireNonNull(root, "root");
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute: " + root);
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 路径解析:相对路径按 root 解析;绝对路径必须在 root 内(前缀匹配,归一化后)。
     *
     * @throws IllegalArgumentException 越界(../ 逃逸或 root 外绝对路径)时
     */
    private Path resolve(Path path) {
        Objects.requireNonNull(path, "path");
        Path normalized = (path.isAbsolute() ? path : root.resolve(path))
            .toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace root: " + path);
        }
        return normalized;
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
