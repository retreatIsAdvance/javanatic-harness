package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 项目输入清单 hash：projectRoot 下根 pom.xml 与 src/**&#47;*.java（递归）的字节 SHA-256 合成，
 * 按相对路径字典序。清单写死在 Provider 文档，不是 LS 索引；hash 只锚定 JH 观察时点。
 */
final class ProjectHash {

    private ProjectHash() {
    }

    /** @return 64 位小写 hex（锚值形状） */
    static String of(Path projectRoot) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            files = walk
                .filter(Files::isRegularFile)
                .filter(path -> inManifest(projectRoot, path))
                .map(projectRoot::relativize)
                .sorted()
                .toList();
        }
        MessageDigest digest = sha256();
        for (Path relative : files) {
            digest.update(relative.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(projectRoot.resolve(relative)));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean inManifest(Path projectRoot, Path path) {
        if ("pom.xml".equals(path.getFileName().toString())
            && path.getParent() != null && path.getParent().equals(projectRoot)) {
            return true;
        }
        Path relative = projectRoot.relativize(path);
        return relative.getNameCount() > 1
            && "src".equals(relative.getName(0).toString())
            && path.getFileName().toString().endsWith(".java");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
