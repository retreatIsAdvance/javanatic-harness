package io.javanatic.harness.lsp.jdtls;

import io.javanatic.harness.lsp.LspDocument;
import io.javanatic.harness.lsp.LspErrorCode;
import io.javanatic.harness.lsp.LspException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Provider 私有输入采样：清单 hash、文档 hash 与同步全文共用一次读取的字节。 */
final class ProviderInputs {

    @FunctionalInterface
    interface FileReader {
        byte[] read(Path path) throws IOException;
    }

    record Document(LspDocument anchor, String text) {
    }

    record Snapshot(String projectHash, Map<URI, Document> documents) {
        Snapshot {
            documents = Map.copyOf(documents);
        }
    }

    private ProviderInputs() {
    }

    static Snapshot sample(Path root, Set<URI> tracked, FileReader reader) {
        try {
            Set<Path> manifest = manifest(root);
            Set<Path> paths = new TreeSet<>(manifest);
            tracked.forEach(uri -> paths.add(documentPath(uri)));
            Map<URI, Document> documents = new TreeMap<>();
            MessageDigest project = sha256();
            for (Path path : paths) {
                byte[] bytes;
                try {
                    bytes = reader.read(path);
                } catch (NoSuchFileException e) {
                    documents.put(path.toUri(), new Document(new LspDocument(path.toUri(), Optional.empty()), ""));
                    continue; // 仅确认不存在才能产生 empty hash；其余读取错误向上传播
                }
                if (manifest.contains(path)) {
                    project.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                    project.update((byte) 0);
                    project.update(bytes);
                    project.update((byte) 0);
                }
                String hash = HexFormat.of().formatHex(sha256().digest(bytes));
                documents.put(path.toUri(), new Document(new LspDocument(path.toUri(), Optional.of(hash)),
                    new String(bytes, StandardCharsets.UTF_8)));
            }
            // 保留调用 URI（file:/ 与 file:/// 等等价拼写不能丢失查询目标）。
            for (URI uri : tracked) {
                Document document = documents.get(documentPath(uri).toUri());
                documents.put(uri, new Document(new LspDocument(uri, document.anchor().sha256()), document.text()));
            }
            return new Snapshot(HexFormat.of().formatHex(project.digest()), documents);
        } catch (IOException e) {
            throw new LspException(LspErrorCode.IO, "cannot sample project inputs: " + e.getMessage(), e);
        }
    }

    private static Set<Path> manifest(Path root) throws IOException {
        Set<Path> paths = new TreeSet<>();
        Path pom = root.resolve("pom.xml");
        if (Files.exists(pom)) {
            paths.add(pom);
        }
        Path sources = root.resolve("src");
        if (Files.exists(sources)) {
            try (Stream<Path> walk = Files.walk(sources)) {
                walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(paths::add);
            }
        }
        return paths;
    }

    private static Path documentPath(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new LspException(LspErrorCode.UNSUPPORTED, "unsupported document uri scheme: " + uri.getScheme());
        }
        return Path.of(uri);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
