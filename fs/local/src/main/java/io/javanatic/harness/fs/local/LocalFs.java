package io.javanatic.harness.fs.local;

import io.javanatic.harness.fs.FsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Files.* 的直接包装：阻塞语义、无沙箱、fail loud。 */
public final class LocalFs implements FsService {

    @Override
    public String read(Path path) throws IOException {
        return Files.readString(path);
    }

    @Override
    public void write(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
    }

    @Override
    public String edit(Path path, String oldString, String newString) throws IOException {
        String content = Files.readString(path);
        int at = content.indexOf(oldString);
        if (at < 0) {
            throw new IllegalArgumentException("oldString not found in " + path);
        }
        String edited = content.substring(0, at) + newString
            + content.substring(at + oldString.length());
        Files.writeString(path, edited);
        return edited;
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(path);
    }

    @Override
    public List<DirEntry> list(Path path) throws IOException {
        try (var stream = Files.list(path)) {
            List<DirEntry> entries = new ArrayList<>();
            for (Path child : stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                entries.add(new DirEntry(child.getFileName().toString(), Files.isDirectory(child)));
            }
            return List.copyOf(entries);
        }
    }
}
